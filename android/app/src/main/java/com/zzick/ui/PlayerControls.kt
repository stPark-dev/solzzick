package com.zzick.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import kotlinx.coroutines.delay

private fun mmss(ms: Long) = "%d:%02d".format(ms / 60_000, ms % 60_000 / 1000)

/** 재생/일시정지 + 끌어서 원하는 시점으로 가는 막대 + "0:03 / 1:12". 상태는 밖에서 받는다. */
@Composable
fun PlayerControls(
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onSeekEnd: () -> Unit = {},
) {
    // 끄는 동안은 손가락 위치를 보여주고, 놓을 때 이동
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = onToggle) {
            if (playing) PauseGlyph(Modifier.size(22.dp).semantics { contentDescription = "일시정지" })
            else Icon(Icons.Filled.PlayArrow, contentDescription = "재생", tint = Brand.Orange)
        }
        Slider(
            value = fraction,
            onValueChange = { f ->
                dragging = f
                onSeek((f * durationMs).toLong()) // 끄는 동안에도 화면이 따라오게
            },
            onValueChangeFinished = {
                dragging = null
                onSeekEnd()
            },
            colors = SliderDefaults.colors(thumbColor = Brand.Orange, activeTrackColor = Brand.Orange, inactiveTrackColor = Brand.Peach),
            modifier = Modifier.weight(1f).testTag("seek"),
        )
        Text(
            "${mmss((fraction * durationMs).toLong())} / ${mmss(durationMs)}",
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = Brand.InkSoft,
        )
    }
}

@Composable
private fun PauseGlyph(modifier: Modifier) {
    Canvas(modifier) {
        val bar = size.width * 0.28f
        val r = CornerRadius(bar / 3)
        drawRoundRect(Brand.Orange, Offset(size.width * 0.14f, 0f), Size(bar, size.height), r)
        drawRoundRect(Brand.Orange, Offset(size.width * 0.58f, 0f), Size(bar, size.height), r)
    }
}

/** ExoPlayer에 붙인 탐색 막대(탐색 방식을 바꾸므로 ExoPlayer만). 위치는 0.1초마다 읽는다. durationMs가 0이면 플레이어 길이를 쓴다. */
@UnstableApi
@Composable
fun PlayerSeekBar(player: ExoPlayer, modifier: Modifier = Modifier, durationMs: Long = 0) {
    var position by remember { mutableLongStateOf(0L) }
    var length by remember { mutableLongStateOf(durationMs) }
    var playing by remember { mutableStateOf(player.playWhenReady) }
    var target by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player, durationMs) {
        while (true) {
            position = player.currentPosition
            length = durationMs.takeIf { it > 0 } ?: player.duration.coerceAtLeast(0)
            playing = player.playWhenReady
            delay(100)
        }
    }
    PlayerControls(
        positionMs = position,
        durationMs = length,
        playing = playing,
        onToggle = { player.playWhenReady = !player.playWhenReady; playing = player.playWhenReady },
        onSeek = {
            // 끄는 동안은 가까운 키프레임으로 빠르게, 놓으면 정확한 위치로
            player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
            player.seekTo(it)
            position = it
            target = it
        },
        onSeekEnd = {
            player.setSeekParameters(SeekParameters.EXACT)
            player.seekTo(target)
        },
        modifier = modifier,
    )
}
