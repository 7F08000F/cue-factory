package com.cuefactory.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.cuefactory.app.ui.CueFactoryApp
import com.cuefactory.app.ui.theme.CueFactoryTheme
import com.cuefactory.app.util.AppLog
import com.cuefactory.app.util.StorageAccess

class MainActivity : ComponentActivity() {
    private val viewModel: CueFactoryViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* proceed; storage all-files is a separate settings toggle */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        enableEdgeToEdge()
        setContent {
            CueFactoryTheme {
                CueFactoryApp(
                    viewModel = viewModel,
                    onRequestAllFilesAccess = { StorageAccess.openAllFilesSettings(this) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val ok = StorageAccess.hasAllFilesAccess()
        AppLog.i("storage all-files=$ok")
        viewModel.setStorageAccess(ok)
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.POST_NOTIFICATIONS
            }
        }
        // Optional legacy storage (self-use convenience on older APIs)
        if (Build.VERSION.SDK_INT < 30) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.READ_EXTERNAL_STORAGE
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
            }
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}
