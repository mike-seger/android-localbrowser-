package com.net128.android.localwebbrowser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.MimeTypeMap
import android.webkit.WebSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import androidx.core.net.toUri
import com.net128.android.localwebbrowser.ui.theme.LocalWebbrowserTheme
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.net.URLConnection

private const val PREFS_NAME = "localwebbrowser"
private const val PREF_LAST_FOLDER_URI = "lastFolderUri"
private const val PREF_LAST_FILE_URI = "lastFileUri"
private const val PREF_LAST_URL = "lastUrl"
private const val PREF_LAST_MODE = "lastMode"

enum class ContentMode {
    LOCAL,
    URL,
}

private fun prefs(context: Context): SharedPreferences =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

private class OpenDocumentTreeReadOnly : ActivityResultContract<Uri?, Uri?>() {
    override fun createIntent(context: Context, input: Uri?): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)

            // Optional: start browsing at the provided tree/document.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && input != null) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, input)
            }
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        if (resultCode != Activity.RESULT_OK) return null
        return intent?.data
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalWebbrowserTheme {
                LocalWebbrowserApp()
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@PreviewScreenSizes
@Composable
fun LocalWebbrowserApp() {
    val context = LocalContext.current

    val savedLastFile = remember { prefs(context).getString(PREF_LAST_FILE_URI, null) }
    val savedLastFolder = remember { prefs(context).getString(PREF_LAST_FOLDER_URI, null) }
    val savedLastUrl = remember { prefs(context).getString(PREF_LAST_URL, null) }
    val savedLastModeRaw = remember { prefs(context).getString(PREF_LAST_MODE, ContentMode.LOCAL.name) }
    val savedLastMode = remember(savedLastModeRaw) {
        runCatching { ContentMode.valueOf(savedLastModeRaw ?: ContentMode.LOCAL.name) }.getOrDefault(ContentMode.LOCAL)
    }

    var selectedFileUri by rememberSaveable { mutableStateOf(savedLastFile) }
    var selectedFolderUri by rememberSaveable { mutableStateOf(savedLastFolder) }
    var selectedUrl by rememberSaveable { mutableStateOf(savedLastUrl) }
    var selectedMode by rememberSaveable { mutableStateOf(savedLastMode) }

    val initialDestination = remember(savedLastMode, savedLastUrl, savedLastFile) {
        when (savedLastMode) {
            ContentMode.URL -> if (!savedLastUrl.isNullOrBlank()) AppDestinations.WEBVIEW else AppDestinations.HOME
            ContentMode.LOCAL -> if (!savedLastFile.isNullOrBlank()) AppDestinations.WEBVIEW else AppDestinations.HOME
        }
    }

    var currentDestination by rememberSaveable { mutableStateOf(initialDestination) }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach {
                item(
                    icon = { Icon(it.icon, contentDescription = it.label) },
                    label = { Text(it.label) },
                    selected = it == currentDestination,
                    onClick = { currentDestination = it }
                )
            }
        }
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            when (currentDestination) {
                AppDestinations.HOME -> Greeting(
                    name = "Android",
                    modifier = Modifier.padding(innerPadding)
                )

                AppDestinations.BROWSER -> BrowserScreen(
                    modifier = Modifier.padding(innerPadding),
                    onFileSelected = { fileUri, folderUri ->
                        selectedFileUri = fileUri
                        selectedFolderUri = folderUri
                        selectedMode = ContentMode.LOCAL

                        prefs(context).edit()
                            .putString(PREF_LAST_FILE_URI, fileUri)
                            .putString(PREF_LAST_FOLDER_URI, folderUri)
                            .putString(PREF_LAST_MODE, ContentMode.LOCAL.name)
                            .apply()

                        currentDestination = AppDestinations.WEBVIEW
                    }
                )

                AppDestinations.URL -> UrlScreen(
                    modifier = Modifier.padding(innerPadding),
                    initialUrl = selectedUrl,
                    onLoadUrl = { url ->
                        selectedUrl = url
                        selectedMode = ContentMode.URL

                        prefs(context).edit()
                            .putString(PREF_LAST_URL, url)
                            .putString(PREF_LAST_MODE, ContentMode.URL.name)
                            .apply()

                        currentDestination = AppDestinations.WEBVIEW
                    }
                )

                AppDestinations.WEBVIEW -> WebViewScreen(
                    modifier = Modifier.padding(innerPadding),
                    uri = selectedFileUri,
                    folderUri = selectedFolderUri,
                    url = selectedUrl,
                    mode = selectedMode,
                )
            }
        }
    }
}

enum class AppDestinations(
    val label: String,
    val icon: ImageVector,
) {
    HOME("Home", Icons.Default.Home),
    BROWSER("Browser", Icons.Default.Folder),
    URL("URL", Icons.Default.Description),
    WEBVIEW("WebView", Icons.Default.Web),
}

@Composable
fun UrlScreen(
    modifier: Modifier = Modifier,
    initialUrl: String?,
    onLoadUrl: (String) -> Unit,
) {
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl ?: "") }
    val trimmed = url.trim()
    val isHttpUrl = trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "Load from URL", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("http(s) URL") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )

        if (trimmed.isNotEmpty() && !isHttpUrl) {
            Text(
                text = "URL must start with http:// or https://",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Button(
            onClick = { onLoadUrl(trimmed) },
            enabled = trimmed.isNotEmpty() && isHttpUrl,
        ) {
            Text("Load")
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    LocalWebbrowserTheme { Greeting("Android") }
}

@Composable
fun BrowserScreen(
    modifier: Modifier = Modifier,
    onFileSelected: (fileUri: String, folderUri: String?) -> Unit,
) {
    val context = LocalContext.current
    val savedLastFolder = remember { prefs(context).getString(PREF_LAST_FOLDER_URI, null) }
    var currentUri by rememberSaveable { mutableStateOf(savedLastFolder) }
    var backStack by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }

    val directoryPicker = rememberLauncherForActivityResult(
        contract = OpenDocumentTreeReadOnly()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            currentUri = uri.toString()
            backStack = emptyList()

            prefs(context).edit().putString(PREF_LAST_FOLDER_URI, currentUri).apply()
        }
    }

    val currentDir = currentUri?.let { uriString ->
        try {
            DocumentFile.fromTreeUri(context, uriString.toUri())
        } catch (_: SecurityException) {
            prefs(context).edit().remove(PREF_LAST_FOLDER_URI).apply()
            currentUri = null
            null
        }
    }
    fun isHtmlFile(file: DocumentFile): Boolean {
        val name = file.name ?: return false
        val lower = name.lowercase()
        return lower.endsWith(".html") || lower.endsWith(".htm")
    }

    val children = currentDir
        ?.listFiles()
        ?.filter { it.isDirectory || isHtmlFile(it) }
        ?.sortedWith(
            compareBy<DocumentFile> { !it.isDirectory }
                .thenBy { it.name?.lowercase() ?: "" }
        )
        .orEmpty()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { directoryPicker.launch(currentDir?.uri) }) {
                Text(text = if (currentDir == null) "Pick SD card" else "Change folder")
            }
            if (backStack.isNotEmpty()) {
                TextButton(onClick = {
                    backStack.lastOrNull()?.let { parent ->
                        currentUri = parent
                        backStack = backStack.dropLast(1)
                    }
                }) { Text("Up") }
            }
        }

        Text(
            text = currentDir?.uri?.toString() ?: "No folder selected",
            style = MaterialTheme.typography.titleMedium
        )

        if (currentDir == null) {
            Text("Choose the external SD card (or any folder) to browse its files.")
        } else if (children.isEmpty()) {
            Text("This folder is empty.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(children, key = { it.uri.toString() }) { file ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (file.isDirectory) {
                                    currentUri?.let { backStack = backStack + it }
                                    currentUri = file.uri.toString()
                                } else {
                                    onFileSelected(file.uri.toString(), currentUri)
                                }
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                            contentDescription = null
                        )
                        Column {
                            Text(file.name ?: "(unknown)", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (file.isDirectory) "Folder" else "File",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WebViewScreen(
    modifier: Modifier = Modifier,
    uri: String?,
    folderUri: String?,
    url: String?,
    mode: ContentMode,
) {
    val context = LocalContext.current
    val logTag = "LocalWebView"
    var lastLoadedKey by remember { mutableStateOf<String?>(null) }
    var interceptedCount by remember { mutableStateOf(0) }
    var interceptedBytes by remember { mutableStateOf(0L) }
    var lastStatsAtMs by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    var loggedFolderContext by remember(folderUri, uri) { mutableStateOf(false) }
    val isLocalMode = mode == ContentMode.LOCAL
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

    fun encodingForMime(mime: String): String? {
        return if (mime.startsWith("text/") || mime == "application/json" || mime == "text/javascript") "utf-8" else null
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
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val headerText = when {
            isLocalMode -> selectedFile?.name ?: uri ?: "No file selected. Pick an HTML file in Browser."
            else -> url ?: "No URL selected. Enter a URL in URL tab."
        }
        Text(text = headerText, style = MaterialTheme.typography.titleMedium)

        val detail = if (isLocalMode) uri else url
        if (!detail.isNullOrBlank()) {
            SelectionContainer { Text(detail, style = MaterialTheme.typography.bodySmall) }
        }

        val shouldShowWebView = if (isLocalMode) uri != null else !url.isNullOrBlank()
        if (shouldShowWebView) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    WebView(context).apply {
                        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
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
                                Log.d(
                                    logTag,
                                    "console(${consoleMessage.messageLevel()}): ${consoleMessage.message()} ($src:${consoleMessage.lineNumber()})"
                                )
                                return true
                            }

                            override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
                                Log.d(logTag, "webChromeClient.onShowCustomView: view=${view?.javaClass?.name}")
                                super.onShowCustomView(view, callback)
                            }

                            override fun onHideCustomView() {
                                Log.d(logTag, "webChromeClient.onHideCustomView")
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

                                                                // Surface media playback failures (often swallowed by apps).
                                                                // This keeps us in logs-only debugging mode without changing the web app.
                                                                view?.evaluateJavascript(
                                                                        """
                                                                                (function() {
                                                                                    if (window.__localweb_media_debug_installed__) return;
                                                                                    window.__localweb_media_debug_installed__ = true;

                                                                                    function safeToString(v) {
                                                                                        try { return String(v); } catch (e) { return '[unstringifiable]'; }
                                                                                    }

                                                                                    window.addEventListener('unhandledrejection', function(ev) {
                                                                                        var r = ev && ev.reason;
                                                                                        console.log('[media-debug] unhandledrejection:', (r && r.name) || '', (r && r.message) || safeToString(r));
                                                                                    });

                                                                                    window.addEventListener('error', function(ev) {
                                                                                        try {
                                                                                            var msg = ev && (ev.message || ev.error && ev.error.message || ev.error);
                                                                                            console.log('[media-debug] window.error:', safeToString(msg));
                                                                                        } catch (e) {}
                                                                                    });

                                                                                    var origPlay = HTMLMediaElement.prototype.play;
                                                                                    HTMLMediaElement.prototype.play = function() {
                                                                                        try {
                                                                                            try {
                                                                                                console.log(
                                                                                                    '[media-debug] play() called:',
                                                                                                    (this && (this.currentSrc || this.src)) || '',
                                                                                                    'paused=' + (!!this.paused),
                                                                                                    'readyState=' + (this.readyState || 0),
                                                                                                    'networkState=' + (this.networkState || 0)
                                                                                                );
                                                                                            } catch (e) {}
                                                                                            var p = origPlay.apply(this, arguments);
                                                                                            if (p && typeof p.then === 'function') {
                                                                                                p.then(function() {
                                                                                                    console.log('[media-debug] play() resolved');
                                                                                                });
                                                                                                p.catch(function(err) {
                                                                                                    console.log('[media-debug] play() rejected:', (err && err.name) || '', (err && err.message) || safeToString(err));
                                                                                                });
                                                                                            }
                                                                                            return p;
                                                                                        } catch (err) {
                                                                                            console.log('[media-debug] play() threw:', (err && err.name) || '', (err && err.message) || safeToString(err));
                                                                                            throw err;
                                                                                        }
                                                                                    };

                                                                                    function logMediaEvent(ev) {
                                                                                        try {
                                                                                            var el = ev && ev.target;
                                                                                            if (!el || !el.tagName) return;
                                                                                            var tag = String(el.tagName).toLowerCase();
                                                                                            if (tag !== 'video' && tag !== 'audio') return;
                                                                                            var src = (el.currentSrc || el.src || '');
                                                                                            var err = el.error;
                                                                                            var errCode = err && typeof err.code === 'number' ? err.code : '';

                                                                                            function dumpMediaState(reason) {
                                                                                                try {
                                                                                                    // Throttle expensive state dumps.
                                                                                                    var now = Date.now();
                                                                                                    var last = el.__localweb_lastDumpTs__ || 0;
                                                                                                    if (reason === 'timeupdate' && (now - last) < 2000) return;
                                                                                                    el.__localweb_lastDumpTs__ = now;

                                                                                                    var rect = (el.getBoundingClientRect && el.getBoundingClientRect()) || null;
                                                                                                    var w = rect ? Math.round(rect.width) : -1;
                                                                                                    var h = rect ? Math.round(rect.height) : -1;

                                                                                                    var cs = (window.getComputedStyle && window.getComputedStyle(el)) || null;
                                                                                                    var display = cs ? cs.display : '';
                                                                                                    var visibility = cs ? cs.visibility : '';
                                                                                                    var opacity = cs ? cs.opacity : '';

                                                                                                    var vW = (tag === 'video' && typeof el.videoWidth === 'number') ? el.videoWidth : '';
                                                                                                    var vH = (tag === 'video' && typeof el.videoHeight === 'number') ? el.videoHeight : '';

                                                                                                    console.log(
                                                                                                        '[media-debug] state:',
                                                                                                        'reason=' + reason,
                                                                                                        'tag=' + tag,
                                                                                                        'src=' + src,
                                                                                                        'currentTime=' + (typeof el.currentTime === 'number' ? el.currentTime.toFixed(3) : ''),
                                                                                                        'duration=' + (typeof el.duration === 'number' ? el.duration.toFixed(3) : ''),
                                                                                                        'muted=' + (!!el.muted),
                                                                                                        'volume=' + (typeof el.volume === 'number' ? el.volume.toFixed(2) : ''),
                                                                                                        (tag === 'video' ? ('videoWH=' + vW + 'x' + vH) : ''),
                                                                                                        'rect=' + w + 'x' + h,
                                                                                                        'display=' + display,
                                                                                                        'visibility=' + visibility,
                                                                                                        'opacity=' + opacity
                                                                                                    );
                                                                                                } catch (e) {}
                                                                                            }

                                                                                            console.log(
                                                                                                '[media-debug] event:', ev.type,
                                                                                                'src=' + src,
                                                                                                'paused=' + (!!el.paused),
                                                                                                'ended=' + (!!el.ended),
                                                                                                'readyState=' + (el.readyState || 0),
                                                                                                'networkState=' + (el.networkState || 0),
                                                                                                (errCode !== '' ? ('errorCode=' + errCode) : '')
                                                                                            );

                                                                                            if (ev.type === 'loadedmetadata' || ev.type === 'playing' || ev.type === 'error') {
                                                                                                dumpMediaState(ev.type);
                                                                                            } else if (ev.type === 'timeupdate') {
                                                                                                dumpMediaState('timeupdate');
                                                                                            }
                                                                                        } catch (e) {}
                                                                                    }

                                                                                    ['play','playing','pause','waiting','stalled','error','loadedmetadata','canplay','canplaythrough','seeking','seeked','timeupdate']
                                                                                        .forEach(function(t) {
                                                                                            document.addEventListener(t, logMediaEvent, true);
                                                                                        });

                                                                                    console.log('[media-debug] installed');
                                                                                })();
                                                                        """.trimIndent(),
                                                                        null
                                                                )
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
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun PreviewLocalWebbrowserApp() {
    LocalWebbrowserTheme { LocalWebbrowserApp() }
}
