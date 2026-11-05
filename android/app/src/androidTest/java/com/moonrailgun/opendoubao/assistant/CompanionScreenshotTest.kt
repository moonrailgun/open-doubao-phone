package com.moonrailgun.opendoubao.assistant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import com.moonrailgun.opendoubao.assistant.core.Screen
import com.moonrailgun.opendoubao.assistant.phone.encodeScreenshot
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class CompanionScreenshotTest {
    @Test fun companionBoundsDimensionsWithoutUpscalingAndKeepsOrdinaryTaskSize() {
        for ((width, height) in listOf(1080 to 2400, 2400 to 1080, 320 to 640)) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            try {
                for (companion in listOf(true, false)) {
                    val decoded = decode(encode(bitmap, companion))
                    try {
                        val ratio = minOf(1f, (if (companion) 1280f else 1440f) / maxOf(width, height))
                        assertEquals((width * ratio).toInt(), decoded.width)
                        assertEquals((height * ratio).toInt(), decoded.height)
                    } finally { decoded.recycle() }
                }
                assertFalse(bitmap.isRecycled)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun highEntropyImageIsResizedUntilItFitsTheByteLimit() {
        val bitmap = Bitmap.createBitmap(1280, 1280, Bitmap.Config.ARGB_8888)
        val random = Random(42)
        bitmap.setPixels(IntArray(1280 * 1280) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) },
            0, 1280, 0, 0, 1280, 1280)
        try {
            assertTrue(Base64.decode(encode(bitmap, false), Base64.NO_WRAP).size > 200 * 1024)
            val image = encode(bitmap, true)
            assertTrue(Base64.decode(image, Base64.NO_WRAP).size <= 200 * 1024)
            val decoded = decode(image)
            try {
                assertTrue(decoded.width in 1 until 1280)
                assertEquals(decoded.width, decoded.height)
            } finally { decoded.recycle() }
            assertFalse(bitmap.isRecycled)
        } finally { bitmap.recycle() }
    }

    @Test fun compressionKeepsWindowAndOverlayMasksBlack() {
        val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        try {
            val image = encodeScreenshot(bitmap, Rect(0, 0, 640, 640), Rect(100, 400, 980, 1600),
                Screen("game", 1, 1080, 2400, 0, emptyList()), Rect(350, 600, 730, 1000), companion = true)
            val decoded = decode(image)
            try {
                fun brightness(x: Int, y: Int) = Color.red(decoded.getPixel(x * decoded.width / 1080, y * decoded.height / 2400))
                assertTrue(brightness(500, 100) < 10)
                assertTrue(brightness(500, 800) < 10)
                assertTrue(brightness(500, 2000) < 10)
                assertTrue(brightness(200, 1200) > 240)
            } finally { decoded.recycle() }
        } finally { bitmap.recycle() }
    }

    @Test fun subtitleChangeSurvivesCompanionEncoding() {
        val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 28f }
            fun subtitle(text: String): String {
                canvas.drawColor(Color.BLACK)
                canvas.drawText(text, 200f, 1800f, paint)
                return encode(bitmap, true)
            }
            assertNotEquals(subtitle("Score: 1"), subtitle("Score: 2"))
        } finally { bitmap.recycle() }
    }

    private fun encode(bitmap: Bitmap, companion: Boolean): String {
        val bounds = Rect(0, 0, bitmap.width, bitmap.height)
        return encodeScreenshot(bitmap, bounds, bounds,
            Screen("game", 1, bitmap.width, bitmap.height, 0, emptyList()), companion = companion)
    }

    private fun decode(image: String): Bitmap = Base64.decode(image, Base64.NO_WRAP).let {
        BitmapFactory.decodeByteArray(it, 0, it.size)!!
    }
}
