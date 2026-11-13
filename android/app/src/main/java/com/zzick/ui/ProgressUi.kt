package com.zzick.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zzick.core.progress.Eta

/** 남은 시간 추정에 쓰는 시계(ms). 테스트에서 바꿔 끼운다. */
val LocalClock = staticCompositionLocalOf<() -> Long> { SystemClock::elapsedRealtime }

/** key(같은 일)마다 진행률이 오를 때 남은 시간 글("약 20초 남음"), 아직 모르면 null. */
@Composable
fun rememberEtaLabel(key: Any, progress: Float): String? {
    val clock = LocalClock.current
    val eta = remember(key) { Eta() }
    return remember(key, progress) { eta.update(clock(), progress)?.let(Eta::label) }
}

/** 분석·만들기 진행 화면: 퍼센트·막대·남은 시간(개선 후보 5)·취소. */
@Composable
fun ProgressPanel(label: String, progress: Float, onCancel: () -> Unit) {
    val eta = rememberEtaLabel(label, progress)
    BouncyLogo(Modifier.height(80.dp))
    Text(label, style = MaterialTheme.typography.titleLarge)
    Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = Brand.Orange)
    SunsetProgress(progress)
    eta?.let { Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Brand.Ink) }
    Text("화면을 켜둔 채 기다려 주세요", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
    PillOutlinedButton(onClick = onCancel) { Text("취소") }
}
