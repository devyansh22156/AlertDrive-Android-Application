package com.example.alertdrive

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import java.nio.IntBuffer

/**
 * Extension function to convert an ImageProxy in RGBA_8888 format
 * into a correctly rotated Bitmap, then close the proxy.
 */
fun ImageProxy.toBitmap(): Bitmap {
    // 1) Read the pixel data from the single plane buffer
    val intBuffer: IntBuffer = planes[0].buffer.asIntBuffer()
    val pixels = IntArray(intBuffer.remaining()).also { intBuffer.get(it) }

    // 2) Create a Bitmap from the pixel array
    val bmp = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

    // 3) Rotate the Bitmap if the image was captured with rotation
    val rotated = if (imageInfo.rotationDegrees != 0) {
        Matrix().apply { postRotate(imageInfo.rotationDegrees.toFloat()) }
            .let { matrix ->
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            }
    } else {
        bmp
    }

    // 4) Close the ImageProxy and return the final Bitmap
    this.close()
    return rotated
}
