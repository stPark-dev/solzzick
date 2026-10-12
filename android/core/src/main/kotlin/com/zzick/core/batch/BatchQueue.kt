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
)

/**
 * 일괄 편집 큐(불변). 모든 연산은 새 큐를 돌려준다.
 * 작업 순서: 분석 단계 일(사람 찾기·분석)을 목록 순서대로 먼저 — 사용자가 결과 목록을 빨리 보도록 — 그다음 렌더.
 */
data class BatchQueue<A>(
    val items: List<BatchItem<A>> = emptyList(),
    val common: EditSettings = EditSettings(),
    private val nextId: Int = 1,
) {
    fun item(id: Int) = items.first { it.id == id }

    fun add(sources: List<String>) = copy(
        items = items + sources.mapIndexed { i, s -> BatchItem<A>(nextId + i, s, Stage.QUEUED) },
        nextId = nextId + sources.size,
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

    fun renderAll() = copy(items = items.map { if (it.stage == Stage.READY) it.copy(stage = Stage.WAITING_RENDER) else it })

    fun onRendered(id: Int, output: String) = update(id) { it.copy(stage = Stage.DONE, output = output, progress = 1f) }

    fun onFailed(id: Int, message: String) = update(id) { it.copy(stage = Stage.FAILED, error = message) }

    /** 대기·진행 중인 일을 취소(완료·준비됨·선택 대기는 그대로). */
    fun cancel(id: Int) = update(id) {
        if (it.stage in CANCELLABLE) it.copy(stage = Stage.FAILED, error = "취소됨") else it
    }

    fun retry(id: Int) = update(id) {
        if (it.stage == Stage.FAILED) BatchItem(it.id, it.source, Stage.QUEUED, settings = it.settings) else it
    }

    fun remove(id: Int) = copy(items = items.filter { it.id != id })

    fun withCommon(settings: EditSettings) = copy(common = settings)

    fun withItemSettings(id: Int, settings: EditSettings) = update(id) { it.copy(settings = settings) }

    fun withWindow(id: Int, window: TrimWindow) = update(id) { it.copy(window = window) }

    fun settingsFor(id: Int): EditSettings = item(id).settings ?: common

    private fun update(id: Int, f: (BatchItem<A>) -> BatchItem<A>) = copy(items = items.map { if (it.id == id) f(it) else it })

    private companion object {
        val CANCELLABLE = setOf(Stage.QUEUED, Stage.SCANNING, Stage.WAITING_ANALYSIS, Stage.ANALYZING, Stage.WAITING_RENDER, Stage.RENDERING)
    }
}
