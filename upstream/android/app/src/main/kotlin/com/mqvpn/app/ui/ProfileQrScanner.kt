// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors
package com.mqvpn.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/** Shared QR scanner for onboarding and the standalone profile importer. */
@Composable
fun rememberProfileQrScanner(onScan: (String) -> Unit, onError: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val handleScan by rememberUpdatedState(onScan)
    val handleError by rememberUpdatedState(onError)
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { handleScan(it) }
    }
    val launchScan = {
        scanner.launch(ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setOrientationLocked(false)
            .setPrompt("Scan the QR code shown by your VPS setup")
            .setBeepEnabled(false))
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchScan() else handleError("Camera permission is needed to scan a QR code.")
    }
    return {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchScan()
        } else camera.launch(Manifest.permission.CAMERA)
    }
}
