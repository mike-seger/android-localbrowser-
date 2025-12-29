package com.net128.android.localwebbrowser

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.net128.android.localwebbrowser.ui.theme.LocalWebbrowserTheme

@OptIn(ExperimentalMaterial3Api::class)
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

    var isWebFullscreen by rememberSaveable { mutableStateOf(false) }

    val initialDestination = remember(savedLastMode, savedLastUrl, savedLastFile) {
        when (savedLastMode) {
            ContentMode.URL -> if (!savedLastUrl.isNullOrBlank()) AppDestinations.WEBVIEW else AppDestinations.HOME
            ContentMode.LOCAL -> if (!savedLastFile.isNullOrBlank()) AppDestinations.WEBVIEW else AppDestinations.HOME
        }
    }

    var currentDestination by rememberSaveable { mutableStateOf(initialDestination) }

    LaunchedEffect(currentDestination) {
        if (currentDestination != AppDestinations.WEBVIEW && isWebFullscreen) {
            isWebFullscreen = false
        }
    }

    val appContent = remember {
        movableContentOf<Boolean> { withNavigationPadding ->
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                contentWindowInsets =
                    if (withNavigationPadding) ScaffoldDefaults.contentWindowInsets else WindowInsets(0, 0, 0, 0),
            ) { innerPadding ->
                val contentModifier = if (withNavigationPadding) Modifier.padding(innerPadding) else Modifier.fillMaxSize()
                when (currentDestination) {
                    AppDestinations.HOME -> Greeting(
                        name = "Android",
                        modifier = contentModifier
                    )

                    AppDestinations.BROWSER -> BrowserScreen(
                        modifier = contentModifier,
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
                        modifier = contentModifier,
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
                        modifier = contentModifier,
                        uri = selectedFileUri,
                        folderUri = selectedFolderUri,
                        url = selectedUrl,
                        mode = selectedMode,
                        onFullscreenChanged = { isWebFullscreen = it },
                    )
                }
            }
        }
    }

    if (isWebFullscreen) {
        // Avoid NavigationSuiteScaffold entirely; it reserves layout even with no items.
        appContent(false)
    } else {
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
            appContent(true)
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

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun PreviewLocalWebbrowserApp() {
    LocalWebbrowserTheme { LocalWebbrowserApp() }
}
