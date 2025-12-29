package com.net128.android.localwebbrowser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.net128.android.localwebbrowser.ui.theme.LocalWebbrowserTheme

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
