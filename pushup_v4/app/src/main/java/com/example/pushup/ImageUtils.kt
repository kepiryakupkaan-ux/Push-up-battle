package com.example.pushup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
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

            // DÜZELTME: birçok telefon (özellikle Samsung/Xiaomi gibi) fotoğrafın piksel
            // verisini hiç döndürmez, "bu kaç derece döndürülerek gösterilmeli" bilgisini
            // sadece EXIF etiketinde tutar. BitmapFactory bunu otomatik uygulamıyor - bu
            // yüzden bazı cihazlarda galeriden seçilen profil fotoğrafı 90°/180° yanlış
            // yönde kaydediliyordu. Önce EXIF'i okuyoruz, sonra piksel verisini buna göre
            // gerçekten döndürüyoruz.
            val orientation = resolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL

            val original = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: return null
            val upright = applyExifRotation(original, orientation)

            val size = minOf(upright.width, upright.height)
            val x = (upright.width - size) / 2
            val y = (upright.height - size) / 2
            val cropped = Bitmap.createBitmap(upright, x, y, size, size)
            val scaled = Bitmap.createScaledBitmap(cropped, TARGET_SIZE, TARGET_SIZE, true)

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    private fun applyExifRotation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
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

