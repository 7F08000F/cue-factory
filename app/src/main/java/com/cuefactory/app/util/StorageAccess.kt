package com.cuefactory.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * Path-based CUE/FLAC scan needs broad filesystem access on Android 11+.
 * Without [Environment.isExternalStorageManager], listing /Music or user albums
 * typically returns empty / SecurityException even if the path "exists".
 */
object StorageAccess {
    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    /** Directory is present and listable by this process. */
    fun canListDirectory(dir: File): Boolean {
        if (!dir.exists()) return false
        if (!dir.isDirectory) return false
        return try {
            dir.listFiles() != null
        } catch (_: SecurityException) {
            false
        }
    }

    fun openAllFilesSettings(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            } catch (_: Exception) {
                // last resort: app details
                val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(details)
            }
        }
    }
}
