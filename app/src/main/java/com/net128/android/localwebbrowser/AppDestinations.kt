package com.net128.android.localwebbrowser

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Web
import androidx.compose.ui.graphics.vector.ImageVector

enum class AppDestinations(
    val label: String,
    val icon: ImageVector,
) {
    HOME("Home", Icons.Default.Home),
    BROWSER("Browser", Icons.Default.Folder),
    URL("URL", Icons.Default.Description),
    WEBVIEW("WebView", Icons.Default.Web),
}
