package com.moonrailgun.opendoubao.assistant

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.moonrailgun.opendoubao.assistant.phone.AvatarCrop
import com.moonrailgun.opendoubao.assistant.phone.BubbleAvatar
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BubbleAvatarCropTest {
    @Test fun selectionWaitsForConfirmationAndCancelPreservesTheAvatar() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val device = UiDevice.getInstance(instrument)
        val savedFile = File(context.noBackupFilesDir, "bubble-avatar.png")
        val original = savedFile.takeIf { it.exists() }?.readBytes()
        val source = File(context.cacheDir, "avatar-crop-source.png")
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        Canvas(bitmap).drawRect(200f, 0f, 400f, 200f, Paint().apply { color = Color.BLUE })
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val filter = IntentFilter(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addDataType("image/*")
        }
        val picker = instrument.addMonitor(filter, ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(source))), true)
        fun click(text: String) {
            assertTrue("Missing $text", device.wait(Until.hasObject(By.text(text)), 8000))
            device.findObject(By.text(text)).click()
        }
        fun select() {
            assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.ScrollView")), 8000))
            UiScrollable(UiSelector().className("android.widget.ScrollView"))
                .scrollIntoView(UiSelector().text("选择图片"))
            // Only this empty test activity allows screenshots; production settings remain protected.
            instrument.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().forEach { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            }
            click("选择图片")
            assertTrue(device.wait(Until.hasObject(By.text("裁切悬浮球头像")), 8000))
        }
        fun currentBytes() = savedFile.takeIf { it.exists() }?.readBytes()
        try {
            val activity = instrument.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            instrument.runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            assertTrue(device.wait(Until.hasObject(By.desc("设置")), 8000))
            device.findObject(By.desc("设置")).click()
            val version = BubbleAvatar.version.value
            select()
            assertArrayEquals(original, currentBytes())
            click("取消")
            assertTrue(device.wait(Until.gone(By.text("裁切悬浮球头像")), 8000))
            assertEquals(version, BubbleAvatar.version.value)
            assertArrayEquals(original, currentBytes())

            select()
            val slider = device.findObject(By.desc("缩放图片")).visibleBounds
            device.click(slider.centerX(), slider.centerY())
            val preview = device.findObject(By.desc("头像裁切预览")).visibleBounds
            device.swipe(preview.centerX(), preview.centerY(), preview.left + 5, preview.centerY(), 30)
            device.takeScreenshot(File(context.getExternalFilesDir(null), "avatar-crop-portrait.png"))
            assertArrayEquals("Dragging must not save before confirmation", original, currentBytes())
            rotateToLandscape()
            assertTrue("Rotation must preserve the pending crop", device.wait(Until.hasObject(By.text("裁切悬浮球头像")), 8000))
            assertArrayEquals("Rotation must not save before confirmation", original, currentBytes())
            click("确认使用")
            assertTrue(device.wait(Until.gone(By.text("裁切悬浮球头像")), 8000))
            val until = SystemClock.uptimeMillis() + 8000
            while (BubbleAvatar.version.value == version && SystemClock.uptimeMillis() < until) SystemClock.sleep(50)
            assertEquals(version + 1, BubbleAvatar.version.value)
            val cropped = BubbleAvatar.load(context)!!
            assertEquals(cropped.width, cropped.height)
            assertTrue("The zoom slider must change the saved crop", cropped.width < 150)
            assertEquals("Rotation must preserve the dragged position", Color.BLUE, cropped.getPixel(cropped.width / 4, cropped.height / 2))
            cropped.recycle()
            val confirmed = currentBytes()

            select()
            assertTrue(device.wait(Until.hasObject(By.desc("缩放图片")), 8000))
            val landscapePreview = device.findObject(By.desc("头像裁切预览")).visibleBounds
            assertEquals("The whole circular preview must fit", landscapePreview.width(), landscapePreview.height())
            device.takeScreenshot(File(context.getExternalFilesDir(null), "avatar-crop-landscape.png"))
            click("取消")
            assertTrue(device.wait(Until.gone(By.text("裁切悬浮球头像")), 8000))
            assertArrayEquals(confirmed, currentBytes())
        } catch (failure: Throwable) {
            device.takeScreenshot(File(context.getExternalFilesDir(null), "avatar-crop-failure.png"))
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "avatar-crop-failure.xml"))
            throw failure
        } finally {
            instrument.removeMonitor(picker)
            instrument.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().forEach { it.finish() }
            }
            source.delete()
            if (original == null) savedFile.delete() else savedFile.writeBytes(original)
        }
    }

    @Test fun landscapeWithLargeTextKeepsCropAndControlsSeparate() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val device = UiDevice.getInstance(instrument)
        val originalFont = device.executeShellCommand("settings get system font_scale").trim()
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        var confirmed: AvatarCrop? = null
        val visible = mutableStateOf(true)
        try {
            device.executeShellCommand("settings put system font_scale 2.0")
            instrument.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            rotateToLandscape()
            instrument.runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().single()
                assertEquals(2f, activity.resources.configuration.fontScale)
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                // Exercise the production dialog without depending on settings-page navigation at 200% text.
                activity.setContent {
                    MaterialTheme {
                        if (visible.value) BubbleAvatarCropDialog(bitmap, false, null,
                            onDismiss = { visible.value = false },
                            onConfirm = { confirmed = it; visible.value = false },
                        )
                    }
                }
            }
            assertTrue(device.wait(Until.hasObject(By.desc("头像裁切预览")), 8000))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "avatar-crop-large-text.png"))
            val preview = device.findObject(By.desc("头像裁切预览")).visibleBounds
            val confirm = device.findObject(By.text("确认使用")).visibleBounds
            assertFalse("The preview must not overlap the confirm button", Rect.intersects(preview, confirm))
            assertEquals("The entire circular preview must fit", preview.width(), preview.height())
            assertTrue("The preview must remain usable", preview.width() >= 80 * context.resources.displayMetrics.density)
            assertTrue("Zoom must remain visible", device.wait(Until.hasObject(By.desc("缩放图片")), 8000))
            val slider = device.findObject(By.desc("缩放图片")).visibleBounds
            device.findObject(By.desc("头像裁切预览"))?.let {
                assertFalse("The preview must not overlap the zoom control", Rect.intersects(it.visibleBounds, slider))
            }
            assertFalse("The zoom control must not overlap the confirm button", Rect.intersects(slider, confirm))
            device.click(slider.centerX(), slider.centerY())
            device.findObject(By.text("确认使用")).click()
            assertTrue(device.wait(Until.gone(By.text("裁切悬浮球头像")), 8000))
            assertNotNull("The confirm button must work at 200% text", confirmed)
            assertTrue("The zoom control must work at 200% text", confirmed!!.size < bitmap.height)
        } finally {
            instrument.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().forEach { it.finish() }
            }
            device.executeShellCommand(if (originalFont == "null") "settings delete system font_scale"
                else "settings put system font_scale $originalFont")
            bitmap.recycle()
        }
    }

    private fun rotateToLandscape() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        var ready = false
        instrument.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().single().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        val deadline = SystemClock.uptimeMillis() + 8000
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            instrument.runOnMainSync {
                ready = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().any {
                        it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    }
            }
            if (!ready) SystemClock.sleep(50)
        }
        assertTrue("Activity must finish rotating", ready)
        instrument.waitForIdleSync()
    }

}
