package com.zzick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zzick.ui.quote.Quote
import com.zzick.ui.quote.QuoteView
import kotlinx.coroutines.launch

/**
 * 첫 화면(상태 없음): 로고 → 영상 선택 → 작업 목록 → 명언. 명언이 한가운데면 클라이밍 편집 앱의 정체성이 흐려져
 * 동작을 위로, 명언은 아래로(사용자 요청). 영상 선택은 1개 편집인지 여러 개(일괄 편집)인지 고르는 창을 띄운다.
 */
@Composable
fun HomeScreen(
    jobCount: Int,
    quote: Quote?,
    onPickOne: () -> Unit,
    onPickMany: () -> Unit,
    onOpenJobs: () -> Unit,
) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.weight(0.5f))
        BouncyLogo(Modifier.height(96.dp))
        Spacer(Modifier.height(18.dp))
        // 첫 화면의 두 동작은 크게(사용자 요청: 너무 작다)
        SunsetButton(onClick = { choosing = true }, modifier = Modifier.fillMaxWidth().height(HOME_BUTTON_HEIGHT)) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
            Text("영상 선택", fontSize = 21.sp, fontWeight = FontWeight.Bold)
        }
        PillOutlinedButton(onClick = onOpenJobs, modifier = Modifier.fillMaxWidth().height(HOME_BUTTON_HEIGHT), color = Brand.HoldBlue) {
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (jobCount > 0) "작업 목록 ($jobCount)" else "작업 목록", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.weight(1f))
        quote?.let { BrandCard(Modifier.fillMaxWidth()) { QuoteView(it, Modifier.padding(horizontal = 20.dp, vertical = 22.dp)) } }
    }
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("어떻게 편집할까요?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SunsetButton(onClick = { choosing = false; onPickOne() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("영상 1개 편집")
                    }
                    Box {
                        PillOutlinedButton(onClick = { choosing = false; onPickMany() }, modifier = Modifier.fillMaxWidth(), color = Brand.HoldBlue) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("여러 영상 일괄 편집")
                        }
                        // 1시 방향의 i — 누르면 설명 말풍선(사용자 요청)
                        // TooltipBox는 받은 align을 무시해 왼쪽 위에 놓였다 — 위치는 감싼 Box가 정한다
                        Box(Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-14).dp)) { BatchInfo() }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosing = false }) { Text("취소", color = Brand.InkSoft) } },
        )
    }
}

private val HOME_BUTTON_HEIGHT = 72.dp

/** 일괄 편집 안내: 작은 i 버튼, 누르면 말풍선. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatchInfo() {
    val tip = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip(caretSize = TooltipDefaults.caretSize, containerColor = Brand.Ink, contentColor = Color.White) {
                Text("여러 영상은 백그라운드에서 차례로 분석하고, 작업 목록에 묶여요.")
            }
        },
        state = tip,
    ) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(Color.White).border(2.dp, Brand.HoldBlue, CircleShape)
                .clickable { scope.launch { tip.show() } }
                .semantics { contentDescription = "일괄 편집 안내" },
            contentAlignment = Alignment.Center,
        ) {
            Text("i", color = Brand.HoldBlue, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}
