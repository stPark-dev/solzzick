package com.zzick.core.batch

import com.zzick.core.trim.TrimWindow
import com.zzick.core.vcam.EditSettings

/**
 * 일괄 편집 항목의 단계.
 * QUEUED → SCANNING(사람 찾기) → [NEEDS_SELECTION → 사용자 선택] → WAITING_ANALYSIS → ANALYZING → READY
 * → (모두 만들기) WAITING_RENDER → RENDERING → DONE. 어느 단계에서든 FAILED 가능(재시도하면 QUEUED).
 */
enum class Stage { QUEUED, SCANNING, NEEDS_SELECTION, WAITING_ANALYSIS, ANALYZING, READY, WAITING_RENDER, RENDERING, DONE, FAILED }

/** 처리기가 다음에 할 일. */
sealed interface Work {
    data class Scan(val id: Int) : Work
    data class Analyze(val id: Int, val tap: Pair<Double, Double>?) : Work
    data class Render(val id: Int) : Work
}

/**
 * 일괄 편집 작업: 영상을 골라 넣을 때마다 하나. 목록을 작업별로 묶어 보고, 공통 설정·모두 만들기는 작업 단위.
 * @param name 사용자가 붙인 이름(없으면 화면에 만든 날짜·시간)
 */
data class BatchJob(val id: Int, val createdAtMs: Long, val name: String?, val common: EditSettings)

/** @param A 분석 결과 타입(앱의 Analysis). core는 내용을 모른다. */
data class BatchItem<A>(
    val id: Int,
    val source: String,
    val stage: Stage,
    val progress: Float = 0f,
    val tap: Pair<Double, Double>? = null,
    val analysis: A? = null,
    val window: TrimWindow? = null,
    val settings: EditSettings? = null, // null이면 공통 설정
    val output: String? = null,
    val error: String? = null,
    val jobId: Int = 0,
)

/**
 * 일괄 편집 큐(불변). 모든 연산은 새 큐를 돌려준다.
 * 작업 순서: 분석 단계 일(사람 찾기·분석)을 목록 순서대로(먼저 만든 작업부터) 먼저 — 사용자가 결과 목록을 빨리 보도록 — 그다음 렌더.
 */
data class BatchQueue<A>(
    val items: List<BatchItem<A>> = emptyList(),
    val jobs: List<BatchJob> = emptyList(),
    internal val nextId: Int = 1, // 저장·복원(BatchQueueCodec)용으로 모듈 안에 공개
    internal val nextJobId: Int = 1,
) {
    fun item(id: Int) = items.first { it.id == id }

    fun job(jobId: Int) = jobs.first { it.id == jobId }

    fun itemsOf(jobId: Int) = items.filter { it.jobId == jobId }

    /** 새 작업 하나로 넣는다(영상이 없으면 그대로). 공통 설정은 common을 주지 않으면 마지막 작업 것 그대로. */
    fun add(sources: List<String>, createdAtMs: Long = 0L, common: EditSettings = jobs.lastOrNull()?.common ?: EditSettings()) =
        if (sources.isEmpty()) this else copy(
            items = items + sources.mapIndexed { i, s -> BatchItem<A>(nextId + i, s, Stage.QUEUED, jobId = nextJobId) },
            jobs = jobs + BatchJob(nextJobId, createdAtMs, null, common),
            nextId = nextId + sources.size,
            nextJobId = nextJobId + 1,
        )

    fun nextWork(): Work? {
        items.firstOrNull { it.stage == Stage.QUEUED || it.stage == Stage.WAITING_ANALYSIS }?.let {
            return if (it.stage == Stage.QUEUED) Work.Scan(it.id) else Work.Analyze(it.id, it.tap)
        }
        return items.firstOrNull { it.stage == Stage.WAITING_RENDER }?.let { Work.Render(it.id) }
    }

    fun onStarted(id: Int) = update(id) {
        val next = when (it.stage) {
            Stage.QUEUED -> Stage.SCANNING
            Stage.WAITING_ANALYSIS -> Stage.ANALYZING
            Stage.WAITING_RENDER -> Stage.RENDERING
            else -> it.stage
        }
        it.copy(stage = next, progress = 0f)
    }

    fun onScanned(id: Int, people: Int) = update(id) {
        when {
            people <= 0 -> it.copy(stage = Stage.FAILED, error = "등반자를 찾지 못했어요")
            people == 1 -> it.copy(stage = Stage.WAITING_ANALYSIS, tap = null)
            else -> it.copy(stage = Stage.NEEDS_SELECTION)
        }
    }

    fun onSelected(id: Int, tap: Pair<Double, Double>) = update(id) { it.copy(stage = Stage.WAITING_ANALYSIS, tap = tap) }

    fun onProgress(id: Int, progress: Float) = update(id) { it.copy(progress = progress) }

    fun onAnalyzed(id: Int, analysis: A, window: TrimWindow) =
        update(id) { it.copy(stage = Stage.READY, analysis = analysis, window = window, progress = 1f) }

    fun renderAll(jobId: Int) =
        copy(items = items.map { if (it.jobId == jobId && it.stage == Stage.READY) it.copy(stage = Stage.WAITING_RENDER) else it })

    fun onRendered(id: Int, output: String) = update(id) { it.copy(stage = Stage.DONE, output = output, progress = 1f) }

    fun onFailed(id: Int, message: String) = update(id) { it.copy(stage = Stage.FAILED, error = message) }

    /** 대기·진행 중인 일을 취소(완료·준비됨·선택 대기는 그대로). */
    fun cancel(id: Int) = update(id) {
        if (it.stage in CANCELLABLE) it.copy(stage = Stage.FAILED, error = "취소됨") else it
    }

    fun retry(id: Int) = update(id) {
        if (it.stage == Stage.FAILED) BatchItem(it.id, it.source, Stage.QUEUED, settings = it.settings, jobId = it.jobId) else it
    }

    /** 영상 하나를 뺀다. 그 작업에 영상이 남지 않으면 작업도 없앤다. */
    fun remove(id: Int): BatchQueue<A> {
        val rest = items.filter { it.id != id }
        return copy(items = rest, jobs = jobs.filter { j -> rest.any { it.jobId == j.id } })
    }

    /** 작업과 그 영상들을 목록에서 뺀다(만든 결과 영상은 갤러리에 남는다). */
    fun removeJob(jobId: Int) = copy(items = items.filter { it.jobId != jobId }, jobs = jobs.filter { it.id != jobId })

    /** 앞뒤 공백을 지운 이름(40자까지). 비면 이름 없음(날짜·시간으로 보인다). */
    fun rename(jobId: Int, name: String) = updateJob(jobId) { it.copy(name = name.trim().take(MAX_NAME).ifEmpty { null }) }

    fun withCommon(jobId: Int, settings: EditSettings) = updateJob(jobId) { it.copy(common = settings) }

    fun withItemSettings(id: Int, settings: EditSettings) = update(id) { it.copy(settings = settings) }

    fun withWindow(id: Int, window: TrimWindow) = update(id) { it.copy(window = window) }

    fun settingsFor(id: Int): EditSettings = item(id).let { it.settings ?: job(it.jobId).common }

    /**
     * 앱을 다시 켰을 때 저장본에서 복원한 큐를 이어서 처리할 수 있게 정리한다.
     * 중간에 끊긴 일은 대기로 되돌리고, 분석 결과는 analysisFor(분석 저장본)로 다시 붙인다 — 없으면 다시 분석.
     * 선택 화면용 첫 장면은 저장하지 않으므로 선택 대기는 사람 찾기부터 다시.
     */
    fun restored(analysisFor: (BatchItem<A>) -> A?): BatchQueue<A> = copy(
        items = items.map {
            when (it.stage) {
                Stage.SCANNING, Stage.NEEDS_SELECTION -> it.copy(stage = Stage.QUEUED, tap = null, progress = 0f)
                Stage.ANALYZING -> it.copy(stage = Stage.WAITING_ANALYSIS, progress = 0f)
                Stage.READY, Stage.WAITING_RENDER, Stage.RENDERING -> {
                    val a = analysisFor(it)
                    when {
                        a == null -> it.copy(stage = Stage.WAITING_ANALYSIS, progress = 0f)
                        it.stage == Stage.READY -> it.copy(analysis = a)
                        else -> it.copy(stage = Stage.WAITING_RENDER, analysis = a, progress = 0f)
                    }
                }
                else -> it
            }
        },
    )

    private fun update(id: Int, f: (BatchItem<A>) -> BatchItem<A>) = copy(items = items.map { if (it.id == id) f(it) else it })

    private fun updateJob(jobId: Int, f: (BatchJob) -> BatchJob) = copy(jobs = jobs.map { if (it.id == jobId) f(it) else it })

    private companion object {
        const val MAX_NAME = 40
        val CANCELLABLE = setOf(Stage.QUEUED, Stage.SCANNING, Stage.WAITING_ANALYSIS, Stage.ANALYZING, Stage.WAITING_RENDER, Stage.RENDERING)
    }
}
