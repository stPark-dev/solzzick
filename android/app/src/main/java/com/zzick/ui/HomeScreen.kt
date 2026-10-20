package com.zzick.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zzick.ui.quote.Quote
import com.zzick.ui.quote.QuoteView

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
        SunsetButton(onClick = { choosing = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("영상 선택", fontSize = 18.sp)
        }
        PillOutlinedButton(onClick = onOpenJobs, modifier = Modifier.fillMaxWidth(), color = Brand.HoldBlue) {
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(if (jobCount > 0) "작업 목록 ($jobCount)" else "작업 목록")
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
                    PillOutlinedButton(onClick = { choosing = false; onPickMany() }, modifier = Modifier.fillMaxWidth(), color = Brand.HoldBlue) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("여러 영상 일괄 편집")
                    }
                    Text(
                        "여러 영상은 백그라운드에서 차례로 분석하고, 작업 목록에 묶여요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Brand.InkSoft,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosing = false }) { Text("취소", color = Brand.InkSoft) } },
        )
    }
}
