package com.zzick.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zzick.core.overlay.Grade
import com.zzick.core.overlay.OverlayInfo

/** "영상에 표시" 한 줄 — 지금 넣을 내용을 요약해 보여주고, 누르면 아래에서 올라오는 시트에서 고른다(편집 화면 배치를 밀지 않게). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlaySection(info: OverlayInfo, onChange: (OverlayInfo) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val summary = listOfNotNull(info.grade?.emoji, info.gym.trim().ifEmpty { null }, "날짜".takeIf { info.showDate })
        .joinToString(" · ").ifEmpty { "없음" }
    Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("영상에 표시", Modifier.weight(1f), fontWeight = FontWeight.Bold)
        Text(summary, style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
        Text("  ›", color = Brand.InkSoft)
    }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }, containerColor = Brand.Cream) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("영상 구석에 표시", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("모두 선택 사항이에요. 하트를 다시 누르면 빠집니다.", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
                OverlayControls(info, onChange)
            }
        }
    }
}

/** 난이도(색깔 하트, 다시 누르면 해제)·암장 이름·촬영 날짜. 모두 선택 사항. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverlayControls(info: OverlayInfo, onChange: (OverlayInfo) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (g in Grade.entries) {
                val selected = info.grade == g
                Box(
                    Modifier.size(38.dp)
                        .background(if (selected) Brand.Peach else Color.White.copy(alpha = 0.7f), CircleShape)
                        .border(if (selected) 2.dp else 0.dp, if (selected) Brand.Orange else Color.Transparent, CircleShape)
                        .clickable { onChange(info.copy(grade = if (selected) null else g)) },
                    contentAlignment = Alignment.Center,
                ) { Text(g.emoji, fontSize = 20.sp) }
            }
        }
        OutlinedTextField(
            value = info.gym,
            onValueChange = { onChange(info.copy(gym = it.take(OverlayInfo.MAX_GYM))) },
            label = { Text("암장 이름") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("gym"),
        )
        // 글자를 눌러도 켜지고 꺼지게 줄 전체를 스위치로
        Row(
            Modifier.fillMaxWidth().toggleable(info.showDate, role = Role.Switch) { onChange(info.copy(showDate = it)) }.testTag("date"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("촬영 날짜", Modifier.weight(1f))
            Switch(checked = info.showDate, onCheckedChange = null)
        }
    }
}
