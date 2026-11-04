package com.moonrailgun.opendoubao.assistant

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.moonrailgun.opendoubao.assistant.phone.AvatarCrop

@Composable internal fun BubbleAvatarCropDialog(
    bitmap: Bitmap, busy: Boolean, error: String?, onDismiss: () -> Unit, onConfirm: (AvatarCrop) -> Unit,
) {
    var crop by rememberSaveable(bitmap, stateSaver = listSaver(
        save = { listOf(it.left, it.top, it.size) },
        restore = { AvatarCrop(it[0], it[1], it[2]) },
    )) { mutableStateOf(AvatarCrop.centered(bitmap.width, bitmap.height)) }
    var viewport by remember { mutableFloatStateOf(0f) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val shortest = minOf(bitmap.width, bitmap.height).toFloat()
    fun move(x: Float, y: Float): Boolean {
        crop = crop.transform(bitmap.width, bitmap.height, viewport, viewport * x, viewport * y)
        return true
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("裁切悬浮球头像") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BoxWithConstraints(Modifier.weight(1f, fill = false)) {
                    val previewSize = minOf(280.dp, maxWidth, maxHeight)
                    Column(
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Canvas(
                            Modifier.size(previewSize)
                                .clip(CircleShape).background(Color.White)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .onSizeChanged { viewport = it.width.toFloat() }
                                .semantics {
                                    contentDescription = "头像裁切预览"
                                    if (!busy) customActions = listOf(
                                        CustomAccessibilityAction("图片向左移动") { move(-.1f, 0f) },
                                        CustomAccessibilityAction("图片向右移动") { move(.1f, 0f) },
                                        CustomAccessibilityAction("图片向上移动") { move(0f, -.1f) },
                                        CustomAccessibilityAction("图片向下移动") { move(0f, .1f) },
                                    )
                                }
                                .pointerInput(bitmap, busy) {
                                    if (!busy) detectTransformGestures { focus, pan, zoom, _ ->
                                        crop = crop.transform(bitmap.width, bitmap.height, viewport, pan.x, pan.y, zoom, focus.x, focus.y)
                                    }
                                },
                        ) {
                            withTransform({
                                scale(size.width / crop.size, size.width / crop.size, Offset.Zero)
                                translate(-crop.left, -crop.top)
                            }) { drawImage(image) }
                        }
                        Text("拖动图片调整位置，双指缩放或拖动下方滑块。圆形内即为头像显示范围。")
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
                Slider(
                    value = shortest / crop.size,
                    onValueChange = { zoom ->
                        crop = crop.transform(bitmap.width, bitmap.height, viewport, 0f, 0f, zoom * crop.size / shortest)
                    },
                    enabled = !busy, valueRange = 1f..5f,
                    modifier = Modifier.semantics { contentDescription = "缩放图片" },
                )
            }
        },
        confirmButton = { TextButton({ onConfirm(crop) }, enabled = !busy) { Text(if (busy) "正在保存…" else "确认使用") } },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("取消") } },
    )
}
