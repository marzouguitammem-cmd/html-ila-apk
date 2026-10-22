package com.tammem.htmlilaapk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.text.BreakIterator

/**
 * App icons are adaptive icons whose whole picture is one 432x432 PNG (108dp at xxxhdpi).
 * Launchers only show the middle 288px, cut to their shape.
 */
object IconMaker {
    const val SIZE = 432
    const val VISIBLE = 288

    val PALETTE = intArrayOf(
        0xFF2F6FED.toInt(), // blue
        0xFF16A34A.toInt(), // green
        0xFFE53935.toInt(), // red
        0xFF8E24AA.toInt(), // purple
        0xFFF57C00.toInt(), // orange
        0xFF00897B.toInt(), // teal
        0xFFD81B60.toInt(), // pink
        0xFF263238.toInt(), // dark
    )

    /** Colored gradient with the first letter (or emoji) of the app name. */
    fun auto(name: String, colorIndex: Int): Bitmap {
        val color = PALETTE[Math.floorMod(colorIndex, PALETTE.size)]
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        bg.shader = LinearGradient(
            0f, 0f, SIZE.toFloat(), SIZE.toFloat(),
            blend(color, Color.WHITE, 0.18f), blend(color, Color.BLACK, 0.22f), Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), bg)

        val letter = firstLetter(name)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 170f
            setShadowLayer(10f, 0f, 4f, 0x40000000)
        }
        val bounds = Rect()
        text.getTextBounds(letter, 0, letter.length, bounds)
        val y = SIZE / 2f - bounds.exactCenterY()
        c.drawText(letter, SIZE / 2f, y, text)
        return bmp
    }

    fun firstLetter(name: String): String {
        val s = name.trim()
        if (s.isEmpty()) return "؟"
        val it = BreakIterator.getCharacterInstance()
        it.setText(s)
        val end = it.next()
        return s.substring(0, if (end > 0) end else 1).uppercase()
    }

    /** A picture from the gallery, scaled so almost all of it stays visible in the icon. */
    fun fromImage(context: Context, uri: Uri): Bitmap {
        val src = decode(context, uri)
        val side = minOf(src.width, src.height)
        val crop = Rect((src.width - side) / 2, (src.height - side) / 2, (src.width + side) / 2, (src.height + side) / 2)
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(averageColor(src))
        val box = 336f
        val off = (SIZE - box) / 2f
        c.drawBitmap(src, crop, RectF(off, off, off + box, off + box), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        if (src != bmp) src.recycle()
        return bmp
    }

    /** What a launcher shows: the middle part of the icon. */
    fun visiblePart(icon: Bitmap): Bitmap {
        val off = (SIZE - VISIBLE) / 2
        return Bitmap.createBitmap(icon, off, off, VISIBLE, VISIBLE)
    }

    fun png(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun decode(context: Context, uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > 1024) {
                    val scale = 1024f / longest
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalArgumentException("Not an image")
    }

    private fun averageColor(b: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(b, 1, 1, true)
        val c = small.getPixel(0, 0)
        if (small != b) small.recycle()
        return Color.rgb(Color.red(c), Color.green(c), Color.blue(c))
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )
}
