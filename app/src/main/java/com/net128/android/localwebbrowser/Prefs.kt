package com.net128.android.localwebbrowser

import android.content.Context
import android.content.SharedPreferences

internal const val PREFS_NAME = "localwebbrowser"
internal const val PREF_LAST_FOLDER_URI = "lastFolderUri"
internal const val PREF_LAST_FILE_URI = "lastFileUri"
internal const val PREF_LAST_URL = "lastUrl"
internal const val PREF_LAST_MODE = "lastMode"

internal fun prefs(context: Context): SharedPreferences =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
