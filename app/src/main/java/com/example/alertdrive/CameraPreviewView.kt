package com.example.alertdrive

import androidx.camera.core.Preview
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView

/**
 * A “dumb” composable that simply displays the given Preview use‑case on screen.
 * All binding is done in MainActivity.
 */
@Composable
fun CameraPreviewView(
    preview: Preview,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }

    AndroidView(factory = { previewView }, modifier = modifier) { view ->
        preview.setSurfaceProvider(view.surfaceProvider)
    }
}
