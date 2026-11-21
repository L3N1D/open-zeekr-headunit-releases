package com.lenid.zoh.aap

import android.graphics.Bitmap
import android.graphics.BitmapFactory

internal fun decodeAlbumArt(bytes: ByteArray): Bitmap? {
    if (bytes.isEmpty()) return null
    return try {
        val opts = BitmapFactory.Options()
        opts.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            opts.inJustDecodeBounds = false
            opts.inSampleSize = 1
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
        var sampleSize = 1
        val maxDim = 720
        while (opts.outWidth / sampleSize > maxDim || opts.outHeight / sampleSize > maxDim) {
            sampleSize *= 2
        }
        opts.inJustDecodeBounds = false
        opts.inSampleSize = sampleSize
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } catch (_: OutOfMemoryError) {
        null
    }
}
