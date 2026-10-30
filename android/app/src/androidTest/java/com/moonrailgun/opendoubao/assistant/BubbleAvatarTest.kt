package com.moonrailgun.opendoubao.assistant

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.moonrailgun.opendoubao.assistant.phone.BubbleAvatar
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BubbleAvatarTest {
    @Test fun importedImageIsSmallAndSurvivesRemovalOfTheOriginal() = withFiles { context, folder ->
        assertNull(BubbleAvatar.load(context))
        val source = picture(folder, 2400, 1200, Color.MAGENTA)
        BubbleAvatar.save(context, Uri.fromFile(source))
        assertTrue(source.delete())

        val avatar = BubbleAvatar.load(context)
        assertNotNull("Keep a private copy after the source disappears", avatar)
        avatar!!
        assertTrue("Do not retain a full-size photo", avatar.width <= 512 && avatar.height <= 512)
        assertEquals(2 * avatar.height, avatar.width)
        assertEquals(Color.MAGENTA, avatar.getPixel(avatar.width / 2, avatar.height / 2))

        BubbleAvatar.reset(context)
        assertNull("Reset must remove the saved image", BubbleAvatar.load(context))
    }

    @Test fun invalidReplacementPreservesThePreviousAvatar() = withFiles { context, folder ->
        BubbleAvatar.save(context, Uri.fromFile(picture(folder, 80, 80, Color.CYAN)))
        val invalid = File(folder, "invalid.png").apply { writeText("not an image") }
        assertTrue(runCatching { BubbleAvatar.save(context, Uri.fromFile(invalid)) }.isFailure)
        val avatar = BubbleAvatar.load(context)
        assertNotNull(avatar)
        assertEquals(Color.CYAN, avatar!!.getPixel(40, 40))
    }

    @Test fun replacementPreservesTransparencyAndDoesNotAccumulateFiles() = withFiles { context, folder ->
        val source = picture(folder, 80, 80, Color.RED)
        BubbleAvatar.save(context, Uri.fromFile(source))
        BubbleAvatar.save(context, Uri.fromFile(picture(folder, 80, 80, Color.TRANSPARENT)))
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
