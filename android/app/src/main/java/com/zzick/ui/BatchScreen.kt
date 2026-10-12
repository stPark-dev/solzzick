package com.zzick.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.zzick.core.batch.BatchItem
import com.zzick.core.batch.BatchQueue
import com.zzick.core.batch.Stage
import com.zzick.core.tracking.pickBox
import com.zzick.core.trim.TrimWindow
import com.zzick.core.vcam.EditSettings
import com.zzick.core.vcam.VirtualCamera
import com.zzick.pipeline.Analysis
import com.zzick.pipeline.BatchProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 일괄 편집 목록에서 일어나는 사용자 동작. */
interface BatchActions {
    fun select(id: Int)
    fun edit(id: Int)
    fun retry(id: Int)
    fun remove(id: Int)
    fun open(id: Int)
    fun renderAll()
    fun commonChanged(s: EditSettings)
    fun close()
}

private fun statusText(item: BatchItem<Analysis>): String {
    val pct = "${(item.progress * 100).toInt()}%"
    return when (item.stage) {
        Stage.QUEUED -> "대기 중"
        Stage.SCANNING -> "등반자 찾는 중"
        Stage.NEEDS_SELECTION -> "등반자 선택 필요"
        Stage.WAITING_ANALYSIS -> "분석 대기"
        Stage.ANALYZING -> "분석 중 $pct"
        Stage.READY -> "준비됨 · ${fmt(item.window?.durationUs ?: 0)}"
        Stage.WAITING_RENDER -> "만들기 대기"
        Stage.RENDERING -> "만드는 중 $pct"
        Stage.DONE -> "완료"
        Stage.FAILED -> "실패: ${item.error ?: "알 수 없는 오류"}"
    }
}

private fun displayName(context: Context, source: String): String {
    val uri = Uri.parse(source)
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: source
}

private fun stageColor(stage: Stage): Color = when (stage) {
    Stage.NEEDS_SELECTION -> Brand.Orange
    Stage.READY -> Brand.HoldBlue
    Stage.DONE -> Brand.Success
    Stage.FAILED -> Brand.HoldRed
    Stage.SCANNING, Stage.ANALYZING, Stage.RENDERING -> Brand.Viewfinder
    else -> Brand.InkSoft
}

/** 일괄 편집 목록(상태 없음): 공통 설정 + 항목별 상태·동작 + 모두 만들기. */
@Composable
fun BatchList(q: BatchQueue<Analysis>, actions: BatchActions) {
    val context = LocalContext.current
    val ready = q.items.count { it.stage == Stage.READY }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("일괄 편집", style = MaterialTheme.typography.titleLarge)
            StatusPill("${q.items.size}개", Brand.Orange, Modifier.padding(start = 8.dp))
            Spacer(Modifier.weight(1f))
            TextButton(onClick = actions::close) { Text("닫기", color = Brand.InkSoft) }
        }
        BrandCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("공통 설정 — 개별로 편집한 영상은 그 설정을 따릅니다", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
                SettingsControls(q.common, actions::commonChanged)
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(q.items, key = { it.id }) { item ->
                val name = remember(item.source) { displayName(context, item.source) }
                val color = stageColor(item.stage)
                BrandCard(Modifier.fillMaxWidth(), accent = color) {
                    Column(Modifier.padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                StatusPill(statusText(item), color)
                            }
                            when (item.stage) {
                                Stage.NEEDS_SELECTION -> TextButton(onClick = { actions.select(item.id) }) { Text("등반자 선택", color = Brand.Orange) }
                                Stage.READY -> TextButton(onClick = { actions.edit(item.id) }) { Text("편집", color = Brand.HoldBlue) }
                                Stage.FAILED -> TextButton(onClick = { actions.retry(item.id) }) { Text("다시 시도", color = Brand.HoldRed) }
                                Stage.DONE -> TextButton(onClick = { actions.open(item.id) }) { Text("보기", color = Brand.Success) }
                                else -> Unit
                            }
                            if (item.stage != Stage.DONE) TextButton(onClick = { actions.remove(item.id) }) { Text("빼기", color = Brand.InkSoft) }
                        }
                        if (item.stage == Stage.ANALYZING || item.stage == Stage.RENDERING) {
                            SunsetProgress(item.progress, Modifier.padding(end = 10.dp), height = 8.dp)
                        }
                    }
                }
            }
        }
        SunsetButton(onClick = actions::renderAll, enabled = ready > 0, modifier = Modifier.fillMaxWidth()) { Text("모두 만들기 ($ready)") }
    }
}

/** 일괄 편집 화면: 처리기 상태를 목록으로 보여주고, 선택·개별 편집 화면으로 오간다. */
@UnstableApi
@Composable
fun BatchScreen(processor: BatchProcessor, onClose: () -> Unit) {
    val context = LocalContext.current
    val q by processor.state.collectAsStateWithLifecycle()
    var selecting by rememberSaveable { mutableStateOf<Int?>(null) }
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }

    val sel = selecting?.let { id -> q.items.firstOrNull { it.id == id && it.stage == Stage.NEEDS_SELECTION } }
    val ed = editing?.let { id -> q.items.firstOrNull { it.id == id && it.stage == Stage.READY } }
    when {
        sel != null -> SelectInBatch(processor, sel.id) { selecting = null }
        ed != null -> EditInBatch(processor, q, ed) { editing = null }
        else -> BatchList(q, object : BatchActions {
            override fun select(id: Int) { selecting = id }
            override fun edit(id: Int) { editing = id }
            override fun retry(id: Int) = processor.retry(id)
            override fun remove(id: Int) = processor.remove(id)
            override fun open(id: Int) {
                val out = q.item(id).output ?: return
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(out), "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
            override fun renderAll() = processor.renderAll()
            override fun commonChanged(s: EditSettings) = processor.setCommon(s)
            override fun close() = onClose()
        })
    }
}

@UnstableApi
@Composable
private fun SelectInBatch(processor: BatchProcessor, id: Int, onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    val preview = processor.preview(id) ?: return onDone()
    var chosen by remember { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("따라갈 등반자를 탭하세요", style = MaterialTheme.typography.titleLarge)
        PersonPicker(preview, chosen) { x, y -> chosen = pickBox(preview.boxes, x, y) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillOutlinedButton(onClick = onDone) { Text("나중에") }
            SunsetButton(onClick = { processor.select(id, preview.boxes[chosen!!].center); onDone() }, enabled = chosen != null) { Text("선택") }
        }
    }
}

@UnstableApi
@Composable
private fun EditInBatch(processor: BatchProcessor, q: BatchQueue<Analysis>, item: BatchItem<Analysis>, onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    val analysis = item.analysis ?: return onDone()
    val settings = q.settingsFor(item.id)
    // 설정이 바뀔 때만 경로를 다시 계산(분석 결과 재사용)
    val path by produceState<Array<DoubleArray>?>(null, analysis, settings) {
        value = withContext(Dispatchers.Default) {
            VirtualCamera.solvePath(analysis.result.track, analysis.fps, analysis.srcW, analysis.srcH, settings.toParams(), analysis.result.body)
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        val p = path
        if (p == null) {
            Spacer(Modifier.weight(1f))
        } else {
            val model = EditModel(Uri.parse(item.source), analysis, item.window!!, analysis.lastPtsUs, settings, p)
            EditPanel(
                model,
                onTrimChange = { s, e ->
                    if (e - s >= 1_000_000) processor.setWindow(item.id, TrimWindow(s.coerceAtLeast(analysis.firstPtsUs), e.coerceAtMost(analysis.lastPtsUs)))
                },
                onSettingsChange = { processor.setItemSettings(item.id, it) },
            ) {
                SunsetButton(onClick = onDone) { Text("완료") }
            }
        }
    }
}
