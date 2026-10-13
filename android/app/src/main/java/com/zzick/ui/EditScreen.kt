package com.zzick.ui

import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.zzick.core.quality.QualityIssue
import com.zzick.core.quality.SourceQuality
import com.zzick.core.render.FrameIndexMap
import com.zzick.core.render.PreviewTransform
import com.zzick.core.vcam.AspectRatio
import com.zzick.core.vcam.EditSettings
import com.zzick.core.vcam.VirtualCamera
import com.zzick.pipeline.ClimbPipeline
import com.zzick.pipeline.OverlayImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal fun fmt(us: Long) = "%d:%04.1f".format(us / 60_000_000, (us % 60_000_000) / 1e6)

/**
 * 결과물 그대로의 실시간 미리보기. 플레이어는 원본을 TextureView에 그리고, 프레임마다(절대 pts) 크롭 경로를
 * 뷰 변환으로 걸어 크롭 영역이 화면을 채우게 한다. 구간이 바뀌면 그 구간만 반복 재생.
 */
/** 미리보기·편집에 필요한 값 묶음(단일·일괄 편집 공용). */
class EditModel(
    val uri: android.net.Uri,
    val analysis: com.zzick.pipeline.Analysis,
    val window: com.zzick.core.trim.TrimWindow,
    val durationUs: Long,
    val settings: EditSettings,
    val path: Array<DoubleArray>,
)

@UnstableApi
@Composable
private fun LivePreview(s: EditModel, modifier: Modifier) {
    val context = LocalContext.current
    val latest = rememberUpdatedState(s)
    val player = remember { ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_ONE; volume = 0f } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val textureView = remember { TextureView(context) }
    DisposableEffect(Unit) {
        val main = Handler(Looper.getMainLooper())
        val values = FloatArray(9)
        val matrix = Matrix()
        player.setVideoTextureView(textureView)
        player.setVideoFrameMetadataListener { ptsUs, _, _, _ ->
            main.post {
                val e = latest.value
                val vw = textureView.width.toDouble()
                val vh = textureView.height.toDouble()
                if (vw <= 0 || vh <= 0) return@post
                val crop = e.path[FrameIndexMap.nearest(e.analysis.result.ptsUs, ptsUs)]
                val m = PreviewTransform.matrix(crop, e.analysis.srcW, e.analysis.srcH, vw, vh)
                for (i in 0 until 9) values[i] = m[i].toFloat()
                matrix.setValues(values)
                textureView.setTransform(matrix)
            }
        }
        // 앱이 뒤로 가면 멈춤(배터리)
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) player.pause() else if (e == Lifecycle.Event.ON_START) player.play() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.release()
        }
    }
    // 손잡이를 끄는 동안 매번 다시 열지 않도록 잠깐 기다렸다가 구간 반영
    LaunchedEffect(s.window) {
        delay(300)
        val start = (s.window.startUs - s.analysis.firstPtsUs) / 1000
        val end = (s.window.endUs - s.analysis.firstPtsUs) / 1000
        val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start).setEndPositionMs(end).build()
        player.setMediaItem(MediaItem.Builder().setUri(s.uri).setClippingConfiguration(clip).build())
        player.prepare()
        player.play()
    }
    // 영상 구석 표시: 렌더와 같은 그림을 출력 크기로 그려 미리보기 크기에 맞춰 얹는다
    val captureDate by produceState<java.time.LocalDate?>(null, s.uri) {
        value = withContext(Dispatchers.IO) { ClimbPipeline(context).captureDate(s.uri) }
    }
    val overlay = s.settings.overlay
    val words = overlay.words(captureDate)
    val mark by produceState<android.graphics.Bitmap?>(null, overlay.grade, words, s.settings.aspect) {
        delay(200) // 암장 이름을 치는 동안 매 글자 다시 그리지 않게
        val (w, h) = VirtualCamera.outputSize(s.settings.toParams())
        value = withContext(Dispatchers.Default) { OverlayImage.render(overlay.grade, words, w, h) }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // 뷰 변환으로 확대된 영상이 상자 밖(다른 UI 위)으로 그려지지 않게 자른다
        Box(Modifier.weight(1f, fill = false).clip(MaterialTheme.shapes.large).drawWithContent {
            drawContent()
            val inset = 10.dp.toPx()
            viewfinderCorners(Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), stroke = 3.dp.toPx())
        }) {
            // 폭·높이 중 모자란 쪽에 맞춰 비율 유지(세로 공간이 부족해도 찌그러지지 않게)
            AndroidView({ textureView }, Modifier.aspectRatio(s.settings.aspect.aspect.toFloat()).clipToBounds())
            mark?.let { Image(it.asImageBitmap(), contentDescription = overlay.text(captureDate), Modifier.matchParentSize(), contentScale = ContentScale.FillBounds) }
        }
        PlayerSeekBar(player, durationMs = s.window.durationUs / 1000)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun ColumnScope.EditPanel(
    s: EditModel,
    onTrimChange: (Long, Long) -> Unit,
    onSettingsChange: (EditSettings) -> Unit,
    buttons: @Composable RowScope.() -> Unit,
) {
    val previewWidth = if (s.settings.aspect == AspectRatio.R16x9) 1f else 0.62f
    LivePreview(s, Modifier.fillMaxWidth(previewWidth).weight(1f, fill = false))

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("구간 ${fmt(s.window.startUs)} – ${fmt(s.window.endUs)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        StatusPill(fmt(s.window.durationUs), Brand.Orange)
    }
    RangeSlider(
        value = s.window.startUs.toFloat()..s.window.endUs.toFloat(),
        onValueChange = { r -> onTrimChange(r.start.toLong(), r.endInclusive.toLong()) },
        valueRange = s.analysis.firstPtsUs.toFloat()..s.durationUs.toFloat(),
        modifier = Modifier.fillMaxWidth(),
    )
    SettingsControls(s.settings, onSettingsChange)
    val issues = remember(s.analysis.source, s.settings) { s.analysis.source?.let { SourceQuality.assess(it, s.settings.toParams()) }.orEmpty() }
    QualityNotice(issues)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), content = buttons)
}

/** 결과가 흐릴 만한 원본이면 이유와 해결책을 한 줄씩. 문제가 없으면 아무것도 그리지 않는다. */
@Composable
fun QualityNotice(issues: Set<QualityIssue>) {
    if (issues.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().background(Brand.Peach, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (issue in issues) {
            val text = when (issue) {
                QualityIssue.UPSCALE -> "확대하면 흐려질 수 있어요 — 원본 해상도에 비해 많이 늘리고 있어요. 확대를 줄이거나 4K로 찍은 원본을 쓰세요."
                QualityIssue.LOW_RESOLUTION -> "원본 해상도가 낮아 흐려질 수 있어요. 카메라 원본이나 4K로 찍은 영상을 쓰세요."
                QualityIssue.RECOMPRESSED -> "다시 압축된 영상 같아요(카톡 등). 갤러리의 카메라 원본을 쓰면 더 선명해요."
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = Brand.Ink)
        }
    }
}

/** 확대·흔들림·비율 조절(편집 화면과 일괄 편집 공통 설정에서 함께 씀). */
@Composable
fun SettingsControls(settings: EditSettings, onChange: (EditSettings) -> Unit) {
    LabeledSlider("확대", "%.2f배".format(settings.zoom), settings.zoom.toFloat(),
        EditSettings.ZOOM_MIN.toFloat()..EditSettings.ZOOM_MAX.toFloat()) { onChange(settings.copy(zoom = it.toDouble())) }
    LabeledSlider("흔들림", if (settings.shake < 0.05) "짐벌" else "%.1f".format(settings.shake), settings.shake.toFloat(),
        0f..EditSettings.SHAKE_MAX.toFloat()) { onChange(settings.copy(shake = it.toDouble())) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("자동 줌", Modifier.weight(1f), fontWeight = FontWeight.Bold)
        Text("크럭스엔 가까이, 큰 동작엔 넓게", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
        Switch(checked = settings.motionZoom, onCheckedChange = { onChange(settings.copy(motionZoom = it)) }, modifier = Modifier.padding(start = 8.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("비율", Modifier.width(52.dp), fontWeight = FontWeight.Bold)
        for (a in AspectRatio.entries) {
            FilterChip(
                selected = settings.aspect == a,
                onClick = { onChange(settings.copy(aspect = a)) },
                label = { Text(a.label, fontWeight = FontWeight.Bold) },
                shape = RoundedCornerShape(50),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Brand.HoldBlue, selectedLabelColor = Color.White, containerColor = Color.White.copy(alpha = 0.7f),
                ),
            )
        }
    }
    OverlaySection(settings.overlay) { onChange(settings.copy(overlay = it)) }
}

@UnstableApi
@Composable
fun ColumnScope.EditScreen(s: EditorState.Edit, vm: EditorViewModel) {
    EditPanel(EditModel(s.uri, s.analysis, s.window, s.durationUs, s.settings, s.path), vm::onTrimChange, vm::onSettingsChange) {
        PillOutlinedButton(onClick = vm::cancel) { Text("취소") }
        PillOutlinedButton(onClick = vm::onReanalyze, color = Brand.HoldBlue) { Text("다시 분석") }
        SunsetButton(onClick = vm::onRender) { Text("만들기") }
    }
}

@Composable
private fun LabeledSlider(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(52.dp), fontWeight = FontWeight.Bold)
        Slider(
            value = v, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = Brand.Orange, activeTrackColor = Brand.Orange, inactiveTrackColor = Brand.Peach),
        )
        Text(value, Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall, color = Brand.Orange, fontWeight = FontWeight.Bold)
    }
}
