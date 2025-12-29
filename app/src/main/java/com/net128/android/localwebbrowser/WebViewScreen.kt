package com.net128.android.localwebbrowser

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.ConsoleMessage
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FilterInputStream
import kotlinx.coroutines.delay
import java.io.InputStream
import java.net.URLConnection
import java.nio.charset.StandardCharsets

@Composable
fun WebViewScreen(
    modifier: Modifier = Modifier,
    uri: String?,
    folderUri: String?,
    url: String?,
    mode: ContentMode,
    onFullscreenChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val logTag = "LocalWebView"
    val activity = context as? Activity

    val injectedMediaDebugJs = remember {
        runCatching {
            context.resources.openRawResource(R.raw.webview_media_optimizations)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.getOrDefault("(function(){console.log('[media-debug] failed to load injected script');})();")
    }
    var lastLoadedKey by remember { mutableStateOf<String?>(null) }
    var interceptedCount by remember { mutableStateOf(0) }
    var interceptedBytes by remember { mutableStateOf(0L) }
    var lastStatsAtMs by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    var loggedFolderContext by remember(folderUri, uri) { mutableStateOf(false) }
    val isLocalMode = mode == ContentMode.LOCAL

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var customView by remember { mutableStateOf<View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var pageFullscreenRequested by remember { mutableStateOf(false) }
    var showExitFullscreen by remember { mutableStateOf(false) }

    val isFullscreen = (customView != null) || pageFullscreenRequested

    LaunchedEffect(isFullscreen) {
        onFullscreenChanged(isFullscreen)
    }

    fun setSystemBarsHidden(hidden: Boolean) {
        val a = activity ?: return
        val window = a.window ?: return
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun exitFullscreen() {
        // Request WebView to exit custom view fullscreen (video).
        // Do NOT clear customView/customViewCallback here: WebView will call onHideCustomView()
        // and we clear state there. Clearing early can race and leave the renderer stuck.
        val hadCustomView = (customView != null) || (customViewCallback != null)
        runCatching { customViewCallback?.onCustomViewHidden() }

        // Ask the page to exit Fullscreen API if it used it.
        pageFullscreenRequested = false
        showExitFullscreen = false
        runCatching {
            webViewRef?.evaluateJavascript(
                "try{document.exitFullscreen&&document.exitFullscreen();}catch(e){}",
                null
            )
        }

        // If we were only in page fullscreen (no custom view), restore system UI now.
        // Otherwise onHideCustomView() will restore it.
        if (!hadCustomView) {
            setSystemBarsHidden(false)
        }
    }

    DisposableEffect(isFullscreen) {
        setSystemBarsHidden(isFullscreen)
        onDispose {
            // Safety: if the composable leaves while fullscreen, restore bars.
            if (isFullscreen) setSystemBarsHidden(false)
        }
    }

    LaunchedEffect(showExitFullscreen, isFullscreen) {
        if (!isFullscreen) return@LaunchedEffect
        if (!showExitFullscreen) return@LaunchedEffect
        delay(2500)
        // Hide if nothing re-triggered it.
        showExitFullscreen = false
    }
    val selectedFile = remember(uri, mode) {
        if (!isLocalMode) return@remember null
        uri?.let { DocumentFile.fromSingleUri(context, Uri.parse(it)) }
    }

    data class FolderContext(
        val treeUri: Uri,
        val baseDocId: String,
    )

    val folderContext = remember(uri, folderUri, mode) {
        if (!isLocalMode) return@remember null
        // Prefer an explicit folder URI from the browser. If it's missing (e.g., restored state),
        // derive the folder from the selected HTML file's document id.
        val baseUri = when {
            !folderUri.isNullOrBlank() -> Uri.parse(folderUri)
            !uri.isNullOrBlank() -> Uri.parse(uri)
            else -> return@remember null
        }

        try {
            val authority = baseUri.authority ?: return@remember null
            val treeId = DocumentsContract.getTreeDocumentId(baseUri)
            val canonicalTreeUri = DocumentsContract.buildTreeDocumentUri(authority, treeId)

            val baseDocId = if (!folderUri.isNullOrBlank()) {
                // Folder URI may be the tree root or a document-in-tree URI.
                if (DocumentsContract.isDocumentUri(context, baseUri)) {
                    DocumentsContract.getDocumentId(baseUri)
                } else {
                    treeId
                }
            } else {
                // No folderUri: use parent folder of the HTML file.
                if (!DocumentsContract.isDocumentUri(context, baseUri)) return@remember null
                val fileDocId = DocumentsContract.getDocumentId(baseUri)
                // Document ids are typically like "volumeId:path/to/file".
                fileDocId.substringBeforeLast('/', missingDelimiterValue = fileDocId)
            }

            FolderContext(treeUri = canonicalTreeUri, baseDocId = baseDocId)
        } catch (_: Exception) {
            null
        }
    }

    data class ResolvedDoc(
        val uri: Uri,
        val isDirectory: Boolean,
        val sizeBytes: Long?,
    )

    class LimitedInputStream(
        input: InputStream,
        private var remaining: Long,
    ) : FilterInputStream(input) {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val v = super.read()
            if (v >= 0) remaining -= 1
            return v
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val toRead = if (remaining < len.toLong()) remaining.toInt() else len
            val n = super.read(b, off, toRead)
            if (n > 0) remaining -= n.toLong()
            return n
        }
    }

    data class ByteRange(
        val startInclusive: Long,
        val endInclusive: Long,
        val totalSize: Long,
    ) {
        val length: Long get() = (endInclusive - startInclusive + 1)
    }

    fun parseByteRangeHeader(rangeHeader: String, totalSize: Long): ByteRange? {
        // Supports single range only.
        // Examples: bytes=0-499, bytes=500-, bytes=-500
        val raw = rangeHeader.trim()
        if (!raw.startsWith("bytes=", ignoreCase = true)) return null
        val spec = raw.substringAfter('=', "").trim()
        if (spec.isBlank()) return null
        if (spec.contains(',')) return null
        val dashIndex = spec.indexOf('-')
        if (dashIndex < 0) return null

        val startPart = spec.substring(0, dashIndex).trim()
        val endPart = spec.substring(dashIndex + 1).trim()

        if (totalSize <= 0L) return null

        val start: Long
        val end: Long

        if (startPart.isEmpty()) {
            // Suffix range: last N bytes
            val suffixLen = endPart.toLongOrNull() ?: return null
            if (suffixLen <= 0) return null
            val clampedSuffix = if (suffixLen > totalSize) totalSize else suffixLen
            start = totalSize - clampedSuffix
            end = totalSize - 1
        } else {
            start = startPart.toLongOrNull() ?: return null
            if (start < 0) return null
            end = if (endPart.isEmpty()) {
                totalSize - 1
            } else {
                endPart.toLongOrNull() ?: return null
            }
        }

        if (start >= totalSize) return null
        val clampedEnd = if (end >= totalSize) totalSize - 1 else end
        if (clampedEnd < start) return null
        return ByteRange(startInclusive = start, endInclusive = clampedEnd, totalSize = totalSize)
    }

    fun serveWithOptionalRange(
        url: Uri,
        resolved: ResolvedDoc,
        mime: String,
        request: WebResourceRequest,
    ): WebResourceResponse {
        val encoding = if (mime.startsWith("text/") || mime == "application/json" || mime == "text/javascript") "utf-8" else null
        val rangeHeader = request.requestHeaders["Range"] ?: request.requestHeaders["range"]
        val totalSize = resolved.sizeBytes

        val method = request.method ?: "GET"
        val isHead = method.equals("HEAD", ignoreCase = true)

        val shouldLog = mime.startsWith("video/") || mime.startsWith("audio/") || !rangeHeader.isNullOrBlank() || isHead

        fun wrapWithCloseLogging(
            input: InputStream,
            expectedBytes: Long?,
            label: String,
        ): InputStream {
            if (!shouldLog) return input
            return object : FilterInputStream(input) {
                private var readBytes: Long = 0

                override fun read(): Int {
                    val v = super.read()
                    if (v >= 0) readBytes += 1
                    return v
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) readBytes += n.toLong()
                    return n
                }

                override fun close() {
                    try {
                        super.close()
                    } finally {
                        val exp = expectedBytes
                        if (exp != null && exp > 0 && readBytes < exp) {
                            Log.w(logTag, "media stream closed early: read=$readBytes expected=$exp $label url=$url")
                        } else {
                            Log.d(logTag, "media stream closed: read=$readBytes expected=${exp ?: -1} $label url=$url")
                        }
                    }
                }
            }
        }

        val range = if (!rangeHeader.isNullOrBlank() && totalSize != null && totalSize > 0) {
            parseByteRangeHeader(rangeHeader, totalSize)
        } else {
            null
        }

        // If there is a Range header but we can't satisfy it, fall back to a full response.
        // (Returning 416 is more correct, but fallback is friendlier and avoids regressions.)
        val response = if (range != null) {
            val headers = mutableMapOf(
                "Accept-Ranges" to "bytes",
                "Content-Range" to "bytes ${range.startInclusive}-${range.endInclusive}/${range.totalSize}",
                "Content-Length" to range.length.toString(),
                "Cache-Control" to "no-store",
            )

            val body: InputStream = if (isHead) {
                ByteArrayInputStream(ByteArray(0))
            } else {
                val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(resolved.uri, "r")
                    ?: throw IllegalStateException("Failed to open file descriptor")
                val fis = FileInputStream(pfd.fileDescriptor)
                try {
                    fis.channel.position(range.startInclusive)
                } catch (_: Exception) {
                    // Fallback to skipping (less efficient).
                    var toSkip = range.startInclusive
                    while (toSkip > 0) {
                        val skipped = fis.skip(toSkip)
                        if (skipped <= 0) break
                        toSkip -= skipped
                    }
                }

                val limited = LimitedInputStream(fis, range.length)
                val closeWrapped = object : FilterInputStream(limited) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            try {
                                pfd.close()
                            } catch (_: Exception) {
                                // ignore
                            }
                        }
                    }
                }

                wrapWithCloseLogging(
                    closeWrapped,
                    expectedBytes = range.length,
                    label = "range=${range.startInclusive}-${range.endInclusive}",
                )
            }

            WebResourceResponse(mime, encoding, 206, "Partial Content", headers, body)
        } else {
            val headers = mutableMapOf(
                "Accept-Ranges" to "bytes",
                "Cache-Control" to "no-store",
            )
            if (totalSize != null && totalSize > 0) {
                headers["Content-Length"] = totalSize.toString()
            }

            val body: InputStream = if (isHead) {
                ByteArrayInputStream(ByteArray(0))
            } else {
                val raw = context.contentResolver.openInputStream(resolved.uri)
                    ?: throw IllegalStateException("Failed to open stream")

                wrapWithCloseLogging(
                    raw,
                    expectedBytes = totalSize,
                    label = "full",
                )
            }

            WebResourceResponse(mime, encoding, 200, "OK", headers, body)
        }

        if (shouldLog) {
            val cr = response.responseHeaders?.get("Content-Range")
            val cl = response.responseHeaders?.get("Content-Length")
            Log.d(
                logTag,
                "serveLocal: ${method.uppercase()} mime=$mime size=${totalSize ?: -1} range=${rangeHeader ?: ""} -> ${response.statusCode} cr=${cr ?: ""} cl=${cl ?: ""} url=$url"
            )
        }

        return response
    }

    // Resolve MIME type for local resources so CSS/JS/fonts load correctly.
    fun guessMimeTypeFromPath(pathOrName: String): String {
        val clean = pathOrName.substringBefore('?').substringBefore('#')
        val ext = clean.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return when (ext) {
            "css" -> "text/css"
            "js", "mjs" -> "text/javascript"
            "json" -> "application/json"
            "html", "htm" -> "text/html"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "mp4" -> "video/mp4"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: URLConnection.guessContentTypeFromName(clean)
                ?: "application/octet-stream"
        }
    }

    fun readTextFromUri(target: Uri): String? {
        return try {
            context.contentResolver.openInputStream(target)?.use { input ->
                input.readBytes().toString(StandardCharsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }
    }

    // SAF resolution can get extremely slow with large folders (e.g., thousands of thumbnails)
    // when using DocumentFile.findFile(). Use DocumentsContract name lookups instead.
    val dirDocIdCache = remember(folderUri) { mutableMapOf<String, String>() }

    fun queryDocumentByDocId(treeUri: Uri, docId: String): ResolvedDoc? {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
        )
        return try {
            context.contentResolver.query(docUri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val actualDocId = cursor.getString(0)
                val mime = cursor.getString(1)
                val isDir = mime == Document.MIME_TYPE_DIR
                val size = if (!cursor.isNull(2)) cursor.getLong(2) else null
                ResolvedDoc(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, actualDocId),
                    isDirectory = isDir,
                    sizeBytes = size,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun queryChildByDisplayName(
        treeUri: Uri,
        parentDocId: String,
        displayName: String,
    ): ResolvedDoc? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
        )

        fun rowToResolved(cursor: android.database.Cursor): ResolvedDoc {
            val docId = cursor.getString(0)
            val mime = cursor.getString(2)
            val isDir = mime == Document.MIME_TYPE_DIR
            val size = if (!cursor.isNull(3)) cursor.getLong(3) else null
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            return ResolvedDoc(uri = docUri, isDirectory = isDir, sizeBytes = size)
        }

        // Some providers support selection; if not, fall back to scanning.
        try {
            context.contentResolver.query(
                childrenUri,
                projection,
                "${Document.COLUMN_DISPLAY_NAME}=?",
                arrayOf(displayName),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) return rowToResolved(cursor)
            }
        } catch (_: Exception) {
            // ignore and fall back
        }

        return try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)
                    if (name == displayName) return rowToResolved(cursor)
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun resolveInFolder(relativePath: String): ResolvedDoc? {
        val fc = folderContext ?: return null
        val treeUri = fc.treeUri
        val cleanPath = relativePath.trimStart('/').substringBefore('?').substringBefore('#')
        if (cleanPath.isBlank()) return null

        // Start at the selected folder itself (may be tree root or a sub-folder).
        val rootDocId = dirDocIdCache.getOrPut("") { fc.baseDocId }

        // Fast path for providers with path-like document IDs (notably ExternalStorageProvider):
        // build docId by appending the relative path to the base folder docId.
        val directDocId = when {
            cleanPath.isBlank() -> rootDocId
            rootDocId.endsWith("/") -> rootDocId + cleanPath
            else -> "$rootDocId/$cleanPath"
        }
        queryDocumentByDocId(treeUri, directDocId)?.let { return it }

        val parts = cleanPath.split('/').filter { it.isNotBlank() }.map { Uri.decode(it) }
        var parentDocId = rootDocId
        var prefix = ""

        // Walk directories (all but last segment)
        for (index in 0 until (parts.size - 1)) {
            val name = parts[index]
            prefix = if (prefix.isEmpty()) name else "$prefix/$name"
            val cachedDocId = dirDocIdCache[prefix]
            if (cachedDocId != null) {
                parentDocId = cachedDocId
                continue
            }

            val child = queryChildByDisplayName(treeUri, parentDocId, name) ?: return null
            if (!child.isDirectory) return null
            val childDocId = DocumentsContract.getDocumentId(child.uri)
            dirDocIdCache[prefix] = childDocId
            parentDocId = childDocId
        }

        // Resolve final segment (child-by-name traversal fallback)
        val leafName = parts.last()
        return queryChildByDisplayName(treeUri, parentDocId, leafName)
    }

    val localHost = "local.web"
    val localBaseUrl = "https://$localHost/"

    fun notFound(url: Uri): WebResourceResponse {
        val body = ByteArrayInputStream("Not Found: $url".toByteArray(StandardCharsets.UTF_8))
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            404,
            "Not Found",
            mapOf("Cache-Control" to "no-store"),
            body
        )
    }

    fun serverError(url: Uri, message: String): WebResourceResponse {
        val body = ByteArrayInputStream(message.toByteArray(StandardCharsets.UTF_8))
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            500,
            "Internal Server Error",
            mapOf("Cache-Control" to "no-store"),
            body
        )
    }

    fun interceptLocalRequest(request: WebResourceRequest): WebResourceResponse? {
        if (!isLocalMode) return null
        // Serve subresources from the selected folder via a stable https:// origin.
        val url = request.url
        if (url.scheme != "https" || url.host != localHost) return null
        val startMs = SystemClock.elapsedRealtime()
        val relPath = url.encodedPath ?: return notFound(url)
        val fc = folderContext
        if (fc == null) {
            if (!loggedFolderContext) {
                Log.e(logTag, "folderContext=null (folderUri=$folderUri uri=$uri)")
                loggedFolderContext = true
            }
            return notFound(url)
        }
        if (!loggedFolderContext) {
            Log.d(logTag, "folderContext treeUri=${fc.treeUri} baseDocId=${fc.baseDocId}")
            loggedFolderContext = true
        }

        val resolveStartMs = SystemClock.elapsedRealtime()
        val resolved = resolveInFolder(relPath) ?: run {
            Log.e(logTag, "Missing local resource: $url (baseDocId=${fc.baseDocId})")
            return notFound(url)
        }
        val resolveMs = SystemClock.elapsedRealtime() - resolveStartMs
        if (resolved.isDirectory) return notFound(url)

        return try {
            val mime = guessMimeTypeFromPath(relPath)
            val streamStartMs = SystemClock.elapsedRealtime()
            val response = serveWithOptionalRange(url, resolved, mime, request)
            val openMs = SystemClock.elapsedRealtime() - streamStartMs

            // Periodic stats (kept lightweight).
            interceptedCount += 1
            val servedBytes = when {
                response.statusCode == 206 -> {
                    response.responseHeaders?.get("Content-Length")?.toLongOrNull() ?: -1L
                }
                else -> resolved.sizeBytes ?: -1L
            }
            if (servedBytes > 0) interceptedBytes += servedBytes
            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - lastStatsAtMs > 2000) {
                Log.d(logTag, "local served: count=$interceptedCount bytes=$interceptedBytes")
                lastStatsAtMs = nowMs
            }

            val totalMs = SystemClock.elapsedRealtime() - startMs
            if (totalMs >= 25 || openMs >= 25) {
                Log.w(logTag, "slow local: ${totalMs}ms (resolve=${resolveMs}ms open=${openMs}ms) $url")
            }

            response
        } catch (e: Exception) {
            serverError(url, "Exception while serving resource: ${e.message}")
        }
    }

    Column(
        modifier = (if (isFullscreen) Modifier.fillMaxSize() else modifier)
            .fillMaxSize()
            .then(if (isFullscreen) Modifier else Modifier.padding(16.dp)),
        verticalArrangement = if (isFullscreen) Arrangement.Top else Arrangement.spacedBy(12.dp)
    ) {
        if (!isFullscreen) {
            val headerText = when {
                isLocalMode -> selectedFile?.name ?: uri ?: "No file selected. Pick an HTML file in Browser."
                else -> url ?: "No URL selected. Enter a URL in URL tab."
            }
            Text(text = headerText, style = MaterialTheme.typography.titleMedium)

            val detail = if (isLocalMode) uri else url
            if (!detail.isNullOrBlank()) {
                SelectionContainer { Text(detail, style = MaterialTheme.typography.bodySmall) }
            }
        }

        val shouldShowWebView = if (isLocalMode) uri != null else !url.isNullOrBlank()
        if (shouldShowWebView) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Keep the WebView attached even while fullscreen custom view is shown.
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            val isDebuggable = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
                            if (isDebuggable) WebView.setWebContentsDebuggingEnabled(true)

                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowContentAccess = true
                            settings.allowFileAccess = true
                            settings.allowUniversalAccessFromFileURLs = true
                            settings.allowFileAccessFromFileURLs = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.textZoom = 100
                            settings.cacheMode = WebSettings.LOAD_DEFAULT
                            settings.mediaPlaybackRequiresUserGesture = false

                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                                    val src = consoleMessage.sourceId().substringAfterLast('/')
                                    val msg = consoleMessage.message() ?: ""

                                    // Host UI signals from injected JS.
                                    if (msg.contains("[media-debug] ui:") && msg.contains("video-touch")) {
                                        // Show the exit icon but do not interfere with the touch.
                                        showExitFullscreen = true
                                    }
                                    if (msg.contains("[media-debug] ui:fullscreen=1")) {
                                        pageFullscreenRequested = true
                                    }
                                    if (msg.contains("[media-debug] ui:fullscreen=0")) {
                                        pageFullscreenRequested = false
                                        if (customView == null) {
                                            showExitFullscreen = false
                                        }
                                    }

                                    Log.d(
                                        logTag,
                                        "console(${consoleMessage.messageLevel()}): $msg ($src:${consoleMessage.lineNumber()})"
                                    )
                                    return true
                                }

                                override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
                                    Log.d(logTag, "webChromeClient.onShowCustomView: view=${view?.javaClass?.name}")
                                    if (view != null) {
                                        customView = view
                                        customViewCallback = callback
                                        pageFullscreenRequested = true
                                        showExitFullscreen = false
                                        setSystemBarsHidden(true)
                                    } else {
                                        super.onShowCustomView(view, callback)
                                    }
                                }

                                override fun onHideCustomView() {
                                    Log.d(logTag, "webChromeClient.onHideCustomView")
                                    customView = null
                                    customViewCallback = null
                                    pageFullscreenRequested = false
                                    showExitFullscreen = false
                                    setSystemBarsHidden(false)

                                    // If fullscreen exit interrupts our transition handlers, the video can be left
                                    // opacity:0/visibility:hidden (and/or with __localweb_loading set). Force-clear.
                                    try {
                                        webViewRef?.evaluateJavascript(
                                            "try{var v=document.querySelector('video');if(v){try{v.classList&&v.classList.remove('__localweb_loading');}catch(e){};var o=document.getElementById('__localweb_video_loading_overlay');if(o){try{o.classList&&o.classList.remove('__show');}catch(e){}};if(v.__localweb_prev_opacity__!==undefined){v.style.opacity=v.__localweb_prev_opacity__;v.__localweb_prev_opacity__=undefined;}else{v.style.opacity='';}if(v.__localweb_prev_visibility__!==undefined){v.style.visibility=v.__localweb_prev_visibility__;v.__localweb_prev_visibility__=undefined;}else{v.style.visibility='';}}}catch(e){}",
                                            null
                                        )
                                    } catch (_: Exception) {
                                        // ignore
                                    }

                                    // WebView often pauses the media when leaving fullscreen; try to resume inline.
                                    try {
                                        webViewRef?.postDelayed({
                                            webViewRef?.evaluateJavascript(
                                                "try{var v=document.querySelector('video'); if(v && v.paused && !v.ended){ v.play(); }}catch(e){}",
                                                null
                                            )
                                            // Nudge layout/draw after fullscreen teardown.
                                            try {
                                                webViewRef?.requestLayout()
                                                webViewRef?.invalidate()
                                            } catch (_: Exception) {
                                                // ignore
                                            }
                                        }, 150)
                                    } catch (_: Exception) {
                                        // ignore
                                    }

                                    super.onHideCustomView()
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                                    return interceptLocalRequest(request) ?: super.shouldInterceptRequest(view, request)
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                    Log.d(logTag, "pageStarted: $url")
                                    super.onPageStarted(view, url, favicon)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    Log.d(logTag, "pageFinished: $url")

                                    // Surface media playback failures + apply WebView layout workaround (loaded from res/raw).
                                    view?.evaluateJavascript(injectedMediaDebugJs, null)
                                    super.onPageFinished(view, url)
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest,
                                    error: android.webkit.WebResourceError
                                ) {
                                    Log.e(
                                        logTag,
                                        "receivedError: ${request.url} mainFrame=${request.isForMainFrame} code=${error.errorCode} desc=${error.description}"
                                    )
                                    super.onReceivedError(view, request, error)
                                }

                                override fun onReceivedHttpError(
                                    view: WebView?,
                                    request: WebResourceRequest,
                                    errorResponse: WebResourceResponse
                                ) {
                                    Log.e(
                                        logTag,
                                        "httpError: ${request.url} mainFrame=${request.isForMainFrame} status=${errorResponse.statusCode} reason=${errorResponse.reasonPhrase}"
                                    )
                                    super.onReceivedHttpError(view, request, errorResponse)
                                }
                            }
                        }
                    },
                    update = { webView ->
                        if (webViewRef !== webView) webViewRef = webView

                        // Keep WebView attached and visible even while fullscreen custom view is shown.
                        // Hiding it (INVISIBLE/GONE) can lead to stuck video rendering after exiting fullscreen.
                        webView.visibility = View.VISIBLE

                        if (isLocalMode) {
                            // Load the HTML itself via SAF, but give it a stable base URL so
                            // relative CSS/JS/fonts/img resolve and can be intercepted.
                            val safeUri = uri
                            if (safeUri != null) {
                                val fileUri = Uri.parse(safeUri)
                                val loadKey = "local@@${safeUri}@@${folderUri ?: ""}"
                                if (lastLoadedKey != loadKey) {
                                    lastLoadedKey = loadKey
                                    val t0 = SystemClock.elapsedRealtime()
                                    val html = readTextFromUri(fileUri)
                                    val readMs = SystemClock.elapsedRealtime() - t0
                                    if (html != null) {
                                        Log.d(
                                            logTag,
                                            "loadDataWithBaseURL base=$localBaseUrl htmlUri=$fileUri htmlBytes=${html.toByteArray(StandardCharsets.UTF_8).size} readMs=${readMs}"
                                        )
                                        webView.loadDataWithBaseURL(
                                            localBaseUrl,
                                            html,
                                            "text/html",
                                            "utf-8",
                                            null
                                        )
                                    } else {
                                        Log.w(logTag, "Falling back to loadUrl for $fileUri")
                                        webView.loadUrl(safeUri)
                                    }
                                }
                            }
                        } else {
                            val targetUrl = url?.trim().orEmpty()
                            val loadKey = "url@@$targetUrl"
                            if (lastLoadedKey != loadKey && targetUrl.isNotBlank()) {
                                lastLoadedKey = loadKey
                                Log.d(logTag, "loadUrl: $targetUrl")
                                webView.loadUrl(targetUrl)
                            }
                        }
                    }
                )

                // If WebView provided a fullscreen custom view (typically video), render it on top.
                if (customView != null) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            FrameLayout(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            }
                        },
                        update = { container ->
                            val v = customView
                            container.removeAllViews()
                            if (v != null) {
                                // Detach from any prior parent.
                                (v.parent as? ViewGroup)?.removeView(v)
                                container.addView(
                                    v,
                                    ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                )
                            }
                        }
                    )
                }

                if (isFullscreen && showExitFullscreen) {
                    IconButton(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .size(36.dp),
                        onClick = { exitFullscreen() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Exit fullscreen",
                        )
                    }
                }
            }
        }
    }
}
