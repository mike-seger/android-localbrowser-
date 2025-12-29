package com.net128.android.localwebbrowser

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import androidx.core.net.toUri
import com.net128.android.localwebbrowser.ui.theme.LocalWebbrowserTheme
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.net.URLConnection

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
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.HOME) }
    var selectedFileUri by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedFolderUri by rememberSaveable { mutableStateOf<String?>(null) }

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
                        currentDestination = AppDestinations.WEBVIEW
                    }
                )

                AppDestinations.WEBVIEW -> WebViewScreen(
                    modifier = Modifier.padding(innerPadding),
                    uri = selectedFileUri,
                    folderUri = selectedFolderUri
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
    WEBVIEW("WebView", Icons.Default.Web),
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
    var currentUri by rememberSaveable { mutableStateOf<String?>(null) }
    var backStack by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }

    val directoryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            currentUri = uri.toString()
            backStack = emptyList()
        }
    }

    val currentDir = currentUri?.let { DocumentFile.fromTreeUri(context, it.toUri()) }
    val children = currentDir
        ?.listFiles()
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
fun WebViewScreen(modifier: Modifier = Modifier, uri: String?, folderUri: String?) {
    val context = LocalContext.current
    val logTag = "LocalWebView"
    val selectedFile = remember(uri) {
        uri?.let { DocumentFile.fromSingleUri(context, Uri.parse(it)) }
    }

    val selectedFolder = remember(folderUri) {
        folderUri?.let { DocumentFile.fromTreeUri(context, Uri.parse(it)) }
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

    fun resolveInFolder(folder: DocumentFile, relativePath: String): DocumentFile? {
        val cleanPath = relativePath.trimStart('/').substringBefore('?').substringBefore('#')
        if (cleanPath.isBlank()) return null
        var current: DocumentFile = folder
        val parts = cleanPath.split('/').filter { it.isNotBlank() }
        for ((index, part) in parts.withIndex()) {
            val next = current.findFile(part) ?: return null
            if (index < parts.lastIndex && !next.isDirectory) return null
            current = next
        }
        return current
    }

    val localHost = "local.web"
    val localBaseUrl = "https://$localHost/"

    fun interceptLocalRequest(request: WebResourceRequest): WebResourceResponse? {
        // Serve subresources from the selected folder via a stable https:// origin.
        val url = request.url
        if (url.scheme != "https" || url.host != localHost) return null
        val folder = selectedFolder ?: return null
        val relPath = url.encodedPath ?: return null

        val file = resolveInFolder(folder, relPath) ?: run {
            Log.w(logTag, "Missing local resource: $url")
            return null
        }
        if (file.isDirectory) return null

        return try {
            val mime = guessMimeTypeFromPath(relPath)
            val stream = context.contentResolver.openInputStream(file.uri) ?: return null
            WebResourceResponse(mime, encodingForMime(mime), stream)
        } catch (_: Exception) {
            null
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = selectedFile?.name ?: uri ?: "No file selected. Pick an HTML file in Browser.",
            style = MaterialTheme.typography.titleMedium
        )
        if (uri != null) {
            SelectionContainer { Text(uri, style = MaterialTheme.typography.bodySmall) }
        }

        if (uri != null) {
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

                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                                val src = consoleMessage.sourceId().substringAfterLast('/')
                                Log.d(
                                    logTag,
                                    "console(${consoleMessage.messageLevel()}): ${consoleMessage.message()} ($src:${consoleMessage.lineNumber()})"
                                )
                                return true
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
                    // Load the HTML itself via SAF, but give it a stable base URL so
                    // relative CSS/JS/fonts/img resolve and can be intercepted.
                    val fileUri = Uri.parse(uri)
                    val html = readTextFromUri(fileUri)
                    if (html != null) {
                        Log.d(logTag, "loadDataWithBaseURL base=$localBaseUrl htmlUri=$fileUri")
                        webView.loadDataWithBaseURL(
                            localBaseUrl,
                            html,
                            "text/html",
                            "utf-8",
                            null
                        )
                    } else {
                        Log.w(logTag, "Falling back to loadUrl for $fileUri")
                        webView.loadUrl(uri)
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
