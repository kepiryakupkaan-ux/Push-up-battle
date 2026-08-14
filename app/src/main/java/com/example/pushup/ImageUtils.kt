package com.example.pushup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Profil fotoğrafını Firestore'a yazmadan önce kare kırpıp küçültür ve Base64'e çevirir.
 * Firestore dokümanları 1 MB ile sınırlı olduğu için (ve okuması/yazması ücretsiz katmanda
 * hızlı kalsın diye) fotoğrafı bilerek küçük tutuyoruz - ayrı bir Firebase Storage kurmaya
 * gerek kalmıyor.
 */
object ImageUtils {
    private const val TARGET_SIZE = 256
    private const val JPEG_QUALITY = 80

    fun uriToProfileBase64(context: Context, uri: Uri): String? {
        return try {
            val resolver = context.contentResolver
            val original = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: return null

            val size = minOf(original.width, original.height)
            val x = (original.width - size) / 2
            val y = (original.height - size) / 2
            val cropped = Bitmap.createBitmap(original, x, y, size, size)
            val scaled = Bitmap.createScaledBitmap(cropped, TARGET_SIZE, TARGET_SIZE, true)

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    fun base64ToBitmap(base64: String?): Bitmap? {
        if (base64.isNullOrBlank()) return null
        return try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }
}
