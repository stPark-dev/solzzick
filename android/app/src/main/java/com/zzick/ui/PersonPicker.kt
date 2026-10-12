package com.zzick.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.zzick.pipeline.PeoplePreview

/** 첫 장면 위에 사람 박스를 그리고, 탭한 위치(이미지 기준 0..1)를 알려준다. 선택된 박스는 아이콘의 네온 뷰파인더로. */
@Composable
fun PersonPicker(preview: PeoplePreview, selected: Int?, onTap: (Double, Double) -> Unit) {
    val bmp = preview.bitmap
    Box(
        Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).clip(RoundedCornerShape(20.dp))
            .pointerInput(preview) {
                detectTapGestures { p -> onTap(p.x / size.width.toDouble(), p.y / size.height.toDouble()) }
            },
    ) {
        Image(bmp.asImageBitmap(), contentDescription = "첫 장면", modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            preview.boxes.forEachIndexed { i, b ->
                val topLeft = Offset((b.x0 * size.width).toFloat(), (b.y0 * size.height).toFloat())
                val boxSize = Size(((b.x1 - b.x0) * size.width).toFloat(), ((b.y1 - b.y0) * size.height).toFloat())
                if (i == selected) {
                    drawRect(Brand.Viewfinder.copy(alpha = 0.15f), topLeft, boxSize)
                    viewfinderCorners(topLeft, boxSize)
                } else {
                    drawRect(Color.White.copy(alpha = 0.85f), topLeft, boxSize, style = Stroke(width = 3f))
                }
            }
        }
    }
}
