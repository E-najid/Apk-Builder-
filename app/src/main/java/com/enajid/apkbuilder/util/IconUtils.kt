package com.enajid.apkbuilder.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

/** Turns a picked image into a square PNG suitable for a launcher icon. */
object IconUtils {

    fun loadIconPng(context: Context, uri: Uri, size: Int = 432): ByteArray? {
        val resolver = context.contentResolver

        // First pass: figure out how big the image is.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // Second pass: decode downsampled.
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val source = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        // Center-crop to a square, then scale.
        val side = minOf(source.width, source.height)
        val cropped = Bitmap.createBitmap(
            source,
            (source.width - side) / 2,
            (source.height - side) / 2,
            side,
            side,
        )
        val scaled = if (side == size) cropped else Bitmap.createScaledBitmap(cropped, size, size, true)

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
}
