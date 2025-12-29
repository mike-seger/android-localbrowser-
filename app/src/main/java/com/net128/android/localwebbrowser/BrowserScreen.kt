package com.net128.android.localwebbrowser

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile

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
