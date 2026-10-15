package com.zzick.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import com.zzick.core.batch.BatchJob
import com.zzick.core.batch.BatchQueue
import com.zzick.core.batch.Stage
import com.zzick.core.store.BatchQueueCodec
import com.zzick.core.tracking.pickBox
import com.zzick.core.trim.TrimWindow
import com.zzick.core.vcam.EditSettings
import com.zzick.core.vcam.VirtualCamera
import com.zzick.pipeline.Analysis
import com.zzick.pipeline.BatchProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 작업 하나의 영상 목록에서 일어나는 사용자 동작(공통 설정·모두 만들기·이름은 그 작업에만). */
interface BatchActions {
    fun select(id: Int)
    fun edit(id: Int)
    fun retry(id: Int)
    fun remove(id: Int)
    fun open(id: Int)
    fun renderAll()
    fun commonChanged(s: EditSettings)
    fun rename(name: String)
    fun close()
}

/** 작업 목록에서 일어나는 사용자 동작. */
interface JobActions {
    fun open(jobId: Int)
    fun rename(jobId: Int, name: String)
    fun remove(jobId: Int)
    fun close()
}

/** 작업 제목: 붙인 이름, 없으면 만든 날짜·시간("10월 15일 12:57"). 작업 개념 전 목록(만든 시각 모름)은 "이전 작업". */
fun jobTitle(job: BatchJob): String = job.name ?: if (job.createdAtMs <= 0L) BatchQueueCodec.PREVIOUS_JOB_NAME
    else SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(job.createdAtMs))

/** "영상 5개 · 완료 3 · 준비 2" — 있는 단계만. */
private fun jobSummary(items: List<BatchItem<Analysis>>): String {
    val parts = mutableListOf("영상 ${items.size}개")
    fun count(label: String, stages: Set<Stage>) = items.count { it.stage in stages }.let { if (it > 0) parts += "$label $it" }
    count("완료", setOf(Stage.DONE))
    count("준비", setOf(Stage.READY))
    count("진행 중", setOf(Stage.QUEUED, Stage.SCANNING, Stage.WAITING_ANALYSIS, Stage.ANALYZING, Stage.WAITING_RENDER, Stage.RENDERING))
    count("선택 필요", setOf(Stage.NEEDS_SELECTION))
    count("실패", setOf(Stage.FAILED))
    return parts.joinToString(" · ")
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

/** 작업 목록(상태 없음): 작업마다 제목·진행 요약, 열기·이름·삭제(확인 후). 최근 작업이 위. */
@Composable
fun JobList(q: BatchQueue<Analysis>, actions: JobActions) {
    var renamingId by rememberSaveable { mutableStateOf<Int?>(null) }
    var removingId by rememberSaveable { mutableStateOf<Int?>(null) }
    val renaming = renamingId?.let { id -> q.jobs.firstOrNull { it.id == id } }
    val removing = removingId?.let { id -> q.jobs.firstOrNull { it.id == id } }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("일괄 편집", style = MaterialTheme.typography.titleLarge)
            StatusPill("작업 ${q.jobs.size}개", Brand.Orange, Modifier.padding(start = 8.dp))
            Spacer(Modifier.weight(1f))
            TextButton(onClick = actions::close) { Text("닫기", color = Brand.InkSoft) }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(q.jobs.reversed(), key = { it.id }) { job ->
                val jobItems = q.itemsOf(job.id)
                val busy = jobItems.any { it.stage in setOf(Stage.SCANNING, Stage.ANALYZING, Stage.RENDERING) }
                BrandCard(Modifier.fillMaxWidth().clickable { actions.open(job.id) }, accent = if (busy) Brand.Viewfinder else Brand.Orange) {
                    Row(Modifier.padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(jobTitle(job), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (job.name != null && job.createdAtMs > 0) {
                                Text(jobTitle(job.copy(name = null)), style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
                            }
                            Text(jobSummary(jobItems), style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
                        }
                        TextButton(onClick = { renamingId = job.id }) { Text("이름", color = Brand.HoldBlue) }
                        TextButton(onClick = { removingId = job.id }) { Text("삭제", color = Brand.InkSoft) }
                    }
                }
            }
        }
    }
    renaming?.let { job -> RenameDialog(job, onDone = { renamingId = null }) { actions.rename(job.id, it) } }
    removing?.let { job ->
        AlertDialog(
            onDismissRequest = { removingId = null },
            title = { Text("작업을 목록에서 뺄까요?") },
            text = { Text("${jobTitle(job)} — 아직 만들지 않은 영상의 진행은 사라지고, 이미 만든 영상은 갤러리에 남아요.") },
            confirmButton = { TextButton(onClick = { actions.remove(job.id); removingId = null }) { Text("빼기", color = Brand.HoldRed) } },
            dismissButton = { TextButton(onClick = { removingId = null }) { Text("취소") } },
        )
    }
}

/** 작업 이름 바꾸기. 비워 두면 날짜·시간으로 보인다. */
@Composable
private fun RenameDialog(job: BatchJob, onDone: () -> Unit, onRename: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(job.name ?: "") }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("작업 이름") },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text(jobTitle(job.copy(name = null))) }) },
        confirmButton = { TextButton(onClick = { onRename(text); onDone() }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDone) { Text("취소") } },
    )
}

/** 작업 하나의 영상 목록(상태 없음): 공통 설정 + 항목별 상태·동작 + 모두 만들기. */
@Composable
fun BatchList(q: BatchQueue<Analysis>, jobId: Int, actions: BatchActions) {
    val context = LocalContext.current
    val job = q.job(jobId)
    val jobItems = q.itemsOf(jobId)
    val ready = jobItems.count { it.stage == Stage.READY }
    var renaming by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = actions::close) { Text("작업 목록", color = Brand.InkSoft) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { renaming = true }) { Text("이름", color = Brand.HoldBlue) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(jobTitle(job), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            StatusPill("${jobItems.size}개", Brand.Orange, Modifier.padding(start = 8.dp))
        }
        BrandCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("이 작업의 공통 설정 — 개별로 편집한 영상은 그 설정을 따릅니다", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
                SettingsControls(job.common, actions::commonChanged)
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(jobItems, key = { it.id }) { item ->
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
    if (renaming) RenameDialog(job, onDone = { renaming = false }, onRename = actions::rename)
}

/**
 * 일괄 편집 화면: 작업 목록 → 작업 하나의 영상 목록 → 선택·개별 편집 화면으로 오간다.
 * @param jobId 열어 둔 작업(null이면 작업 목록). 없어진 작업이면 작업 목록.
 */
@UnstableApi
@Composable
fun BatchScreen(processor: BatchProcessor, jobId: Int?, onOpenJob: (Int?) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val q by processor.state.collectAsStateWithLifecycle()
    var selecting by rememberSaveable { mutableStateOf<Int?>(null) }
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }

    val job = jobId?.let { id -> q.jobs.firstOrNull { it.id == id } }
    // 열어 둔 작업이 없어졌으면(마지막 영상을 뺌 등) 작업 목록으로 — 뒤로 가기가 한 번 헛돌지 않게
    if (jobId != null && job == null) LaunchedEffect(jobId) { onOpenJob(null) }
    if (job == null) {
        JobList(q, object : JobActions {
            override fun open(jobId: Int) = onOpenJob(jobId)
            override fun rename(jobId: Int, name: String) = processor.rename(jobId, name)
            override fun remove(jobId: Int) = processor.removeJob(jobId)
            override fun close() = onClose()
        })
        return
    }
    // 열어 둔 작업의 영상만(다른 작업의 선택 화면이 저절로 뜨지 않게). 대상이 사라졌으면 기억도 지운다
    val sel = selecting?.let { id -> q.items.firstOrNull { it.id == id && it.jobId == job.id && it.stage == Stage.NEEDS_SELECTION } }
    val ed = editing?.let { id -> q.items.firstOrNull { it.id == id && it.jobId == job.id && it.stage == Stage.READY } }
    if (selecting != null && sel == null) SideEffect { selecting = null }
    if (editing != null && ed == null) SideEffect { editing = null }
    when {
        sel != null -> SelectInBatch(processor, sel.id) { selecting = null }
        ed != null -> EditInBatch(processor, q, ed) { editing = null }
        else -> BatchList(q, job.id, object : BatchActions {
            override fun select(id: Int) { selecting = id }
            override fun edit(id: Int) { editing = id }
            override fun retry(id: Int) = processor.retry(id)
            override fun remove(id: Int) = processor.remove(id)
            override fun open(id: Int) {
                val out = q.item(id).output ?: return
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(out), "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
            override fun renderAll() = processor.renderAll(job.id)
            override fun commonChanged(s: EditSettings) = processor.setCommon(job.id, s)
            override fun rename(name: String) = processor.rename(job.id, name)
            override fun close() = onOpenJob(null)
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
