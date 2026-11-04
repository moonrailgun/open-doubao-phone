package com.moonrailgun.opendoubao.assistant

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.moonrailgun.opendoubao.assistant.phone.BubbleAvatar
import com.moonrailgun.opendoubao.assistant.phone.AvatarCrop
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BubbleAvatarTest {
    @Test fun importedImageIsSmallAndSurvivesRemovalOfTheOriginal() = withFiles { context, folder ->
        assertNull(BubbleAvatar.load(context))
        val source = picture(folder, 2400, 1200, Color.MAGENTA)
        save(context, source)
        assertTrue(source.delete())

        val avatar = BubbleAvatar.load(context)
        assertNotNull("Keep a private copy after the source disappears", avatar)
        avatar!!
        assertTrue("Do not retain a full-size photo", avatar.width <= 512 && avatar.height <= 512)
        assertEquals(avatar.height, avatar.width)
        assertEquals(Color.MAGENTA, avatar.getPixel(avatar.width / 2, avatar.height / 2))

        BubbleAvatar.reset(context)
        assertNull("Reset must remove the saved image", BubbleAvatar.load(context))
    }

    @Test fun invalidReplacementPreservesThePreviousAvatar() = withFiles { context, folder ->
        save(context, picture(folder, 80, 80, Color.CYAN))
        val invalid = File(folder, "invalid.png").apply { writeText("not an image") }
        assertTrue(runCatching { save(context, invalid) }.isFailure)
        val avatar = BubbleAvatar.load(context)
        assertNotNull(avatar)
        assertEquals(Color.CYAN, avatar!!.getPixel(40, 40))
    }

    @Test fun replacementPreservesTransparencyAndDoesNotAccumulateFiles() = withFiles { context, folder ->
        val source = picture(folder, 80, 80, Color.RED)
        save(context, source)
        save(context, picture(folder, 80, 80, Color.TRANSPARENT))
        assertTrue(source.delete())
        val avatar = BubbleAvatar.load(context)!!
        assertEquals(0, Color.alpha(avatar.getPixel(40, 40)))
        assertEquals(1, folder.listFiles()!!.size)
        BubbleAvatar.reset(context)
        assertTrue(folder.listFiles()!!.isEmpty())
    }

    @Test fun unreadableSavedImageFallsBackToDefault() = withFiles { context, folder ->
        File(folder, "bubble-avatar.png").writeText("damaged image")
        assertNull(BubbleAvatar.load(context))
        BubbleAvatar.reset(context)
        assertTrue(folder.listFiles()!!.isEmpty())
    }

    @Test fun selectingOnlyPreviewsTheImageAndSavingUsesTheChosenRegion() = withFiles { context, folder ->
        save(context, picture(folder, 80, 80, Color.CYAN))
        val source = picture(folder, 400, 200, Color.RED)
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        Canvas(bitmap).drawRect(200f, 0f, 400f, 200f, Paint().apply { color = Color.BLUE })
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val version = BubbleAvatar.version.value
        val selected = BubbleAvatar.decode(context, Uri.fromFile(source))
        try {
            assertEquals("Selection must not publish an avatar change", version, BubbleAvatar.version.value)
            assertEquals(Color.CYAN, BubbleAvatar.load(context)!!.getPixel(40, 40))
            BubbleAvatar.save(context, selected, AvatarCrop(200f, 0f, 200f))
            val avatar = BubbleAvatar.load(context)!!
            assertEquals(200, avatar.width)
            assertEquals(200, avatar.height)
            assertEquals(Color.BLUE, avatar.getPixel(10, 100))
            assertEquals(Color.BLUE, avatar.getPixel(190, 100))
            assertFalse("Saving must not recycle the preview", selected.isRecycled)
            assertTrue(runCatching { BubbleAvatar.save(context, selected, AvatarCrop(300f, 0f, 200f)) }.isFailure)
            assertEquals(Color.BLUE, BubbleAvatar.load(context)!!.getPixel(100, 100))
        } finally { selected.recycle() }
    }

    private fun save(context: ContextWrapper, source: File) {
        val bitmap = BubbleAvatar.decode(context, Uri.fromFile(source))
        try { BubbleAvatar.save(context, bitmap, AvatarCrop.centered(bitmap.width, bitmap.height)) }
        finally { bitmap.recycle() }
    }

    private fun picture(folder: File, width: Int, height: Int, color: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return File(folder, "source.png").also { file ->
            try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
        }
    }

    private fun withFiles(test: (ContextWrapper, File) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(target.cacheDir, "bubble-avatar-test").apply { deleteRecursively(); mkdirs() }
        val context = object : ContextWrapper(target) { override fun getNoBackupFilesDir() = folder }
        try { test(context, folder) } finally { folder.deleteRecursively() }
    }
}
