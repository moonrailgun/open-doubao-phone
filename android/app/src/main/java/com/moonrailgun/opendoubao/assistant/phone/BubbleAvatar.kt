package com.moonrailgun.opendoubao.assistant.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

object BubbleAvatar {
    private val changes = MutableStateFlow(0)
    val version = changes.asStateFlow()

    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "bubble-avatar.png"))

    @Synchronized fun load(context: Context): Bitmap? = try {
        file(context).openRead().use { BitmapFactory.decodeStream(it) }
    } catch (_: IOException) { null }

    @Synchronized fun save(context: Context, uri: Uri) {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > 512) decoder.setTargetSize(
                (info.size.width.toLong() * 512 / longest).toInt().coerceAtLeast(1),
                (info.size.height.toLong() * 512 / longest).toInt().coerceAtLeast(1),
            )
        }
        try {
            val target = file(context)
            val output = target.startWrite()
            try {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("无法保存头像")
                target.finishWrite(output)
            } catch (e: Exception) {
                target.failWrite(output)
                throw e
            }
            changes.value++
        } finally { bitmap.recycle() }
    }

    @Synchronized fun reset(context: Context) {
        val target = file(context)
        target.delete()
        if (target.baseFile.exists()) throw IOException("无法恢复默认头像")
        changes.value++
    }
}
