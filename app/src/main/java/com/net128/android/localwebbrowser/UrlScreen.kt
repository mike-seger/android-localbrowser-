package com.net128.android.localwebbrowser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

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
