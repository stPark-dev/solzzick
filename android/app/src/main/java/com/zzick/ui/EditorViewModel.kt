package com.zzick.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.zzick.core.vcam.EditSettings
import com.zzick.core.tracking.pickBox
import com.zzick.core.trim.TrimWindow
import com.zzick.pipeline.Analysis
import com.zzick.pipeline.AnalysisStore
import com.zzick.pipeline.ClimbPipeline
import com.zzick.pipeline.Gallery
import com.zzick.pipeline.OverlayPrefs
import com.zzick.pipeline.PeoplePreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

sealed interface EditorState {
    data object Idle : EditorState
    data object Scanning : EditorState
    data class Select(val uri: Uri, val preview: PeoplePreview, val selected: Int?) : EditorState
    data class Analyzing(val progress: Float) : EditorState
    /**
     * 편집 단계: 구간(자동 제안 → 손잡이 조정)·확대·흔들림·비율. path는 현재 설정의 카메라 경로(미리보기·렌더 공용).
     * durationUs는 영상 마지막 프레임 시각.
     */
    data class Edit(
        val uri: Uri,
        val analysis: Analysis,
        val window: TrimWindow,
        val durationUs: Long,
        val settings: EditSettings,
        val path: Array<DoubleArray>,
    ) : EditorState
    data class Rendering(val progress: Float) : EditorState
    data class Done(val galleryUri: Uri) : EditorState
    data class Failed(val message: String) : EditorState
}

@UnstableApi
class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val pipeline = ClimbPipeline(app)
    private val store = AnalysisStore(app)
    private val overlayPrefs = OverlayPrefs(app)
    // 디코더가 GL 컨텍스트를 호출 스레드에 만들므로 무거운 작업은 전용 단일 스레드에서
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val _state = MutableStateFlow<EditorState>(EditorState.Idle)
    val state: StateFlow<EditorState> = _state
    private var job: Job? = null

    /** 전에 분석한 영상이면 저장본으로 바로 편집 화면, 아니면 등반자 찾기부터. */
    fun onVideoPicked(uri: Uri) = launchSafely {
        _state.value = EditorState.Scanning
        withContext(Dispatchers.IO) { pipeline.requireReadable(uri) }
        val saved = withContext(Dispatchers.IO) { store.load(uri) }
        if (saved != null) toEdit(uri, saved.analysis) else scan(uri)
    }

    /** 편집 화면에서: 저장본을 무시하고 등반자 선택부터 다시(엉뚱한 사람을 따라갔을 때). */
    fun onReanalyze() {
        val e = _state.value as? EditorState.Edit ?: return
        launchSafely {
            _state.value = EditorState.Scanning
            scan(e.uri)
        }
    }

    private suspend fun scan(uri: Uri) {
        val preview = withContext(worker) { pipeline.findPeople(uri, isCancelled = cancelledCheck()) }
            ?: throw IllegalStateException("영상에서 사람을 찾지 못했어요")
        _state.value = EditorState.Select(uri, preview, selected = if (preview.boxes.size == 1) 0 else null)
    }

    private suspend fun toEdit(uri: Uri, analysis: Analysis) {
        val settings = EditSettings(overlay = overlayPrefs.load()) // 암장 이름·날짜 표시는 지난번 그대로
        val path = withContext(Dispatchers.Default) { pipeline.solvePath(analysis, settings.toParams()) }
        _state.value = EditorState.Edit(uri, analysis, analysis.suggestTrim(), analysis.lastPtsUs, settings, path)
    }

    /** 선택 화면에서 탭한 위치(이미지 기준 0..1)에 가장 알맞은 사람 박스를 고른다. */
    fun onTap(x: Double, y: Double) {
        val s = _state.value as? EditorState.Select ?: return
        val i = pickBox(s.preview.boxes, x, y)
        _state.value = s.copy(selected = i)
    }

    fun onStart() {
        val s = _state.value as? EditorState.Select ?: return
        val box = s.preview.boxes[s.selected ?: return]
        // 한 명뿐이면 단일 포즈 모드(검출률이 더 높음), 여러 명이면 탭한 사람을 따라간다
        val tap = if (s.preview.boxes.size > 1) box.center else null
        launchSafely {
            _state.value = EditorState.Analyzing(0f)
            val cancelled = cancelledCheck()
            val analysis = withContext(worker) {
                // 취소 직후 작업 스레드가 보내는 마지막 진행률이 화면을 되돌리지 않도록
                pipeline.analyze(s.uri, tap, isCancelled = cancelled) { if (!cancelled()) _state.value = EditorState.Analyzing(it) }
            }
            withContext(Dispatchers.IO) { store.save(s.uri, analysis, tap) }
            toEdit(s.uri, analysis)
        }
    }

    private var pathJob: Job? = null

    /** 슬라이더·비율 변경 → 경로 재계산(분석 결과 재사용). 연속 변경 시 이전 계산은 버린다. */
    fun onSettingsChange(settings: EditSettings) {
        val e = _state.value as? EditorState.Edit ?: return
        _state.value = e.copy(settings = settings) // 슬라이더 위치는 즉시 반영
        if (settings.overlay != e.settings.overlay) overlayPrefs.save(settings.overlay)
        if (settings.copy(overlay = e.settings.overlay) == e.settings) return // 표시만 바뀜 — 카메라 경로는 그대로
        pathJob?.cancel()
        pathJob = viewModelScope.launch {
            val path = withContext(Dispatchers.Default) { pipeline.solvePath(e.analysis, settings.toParams()) }
            val cur = _state.value as? EditorState.Edit ?: return@launch
            if (cur.settings == settings) _state.value = cur.copy(path = path)
        }
    }

    /** 타임라인 손잡이 조정. 최소 1초는 남긴다. */
    fun onTrimChange(startUs: Long, endUs: Long) {
        val t = _state.value as? EditorState.Edit ?: return
        val start = startUs.coerceIn(t.analysis.firstPtsUs, t.durationUs)
        val end = endUs.coerceIn(t.analysis.firstPtsUs, t.durationUs)
        if (end - start < MIN_TRIM_US) return
        _state.value = t.copy(window = TrimWindow(start, end))
    }

    fun onRender() {
        val t = _state.value as? EditorState.Edit ?: return
        launchSafely {
            _state.value = EditorState.Rendering(0f)
            val ctx = getApplication<Application>()
            val name = "zzick_${System.currentTimeMillis()}.mp4"
            val out = File(ctx.cacheDir, name)
            val galleryUri = try {
                pipeline.render(t.uri, t.analysis, t.settings.toParams(), out, t.window, overlay = t.settings.overlay) {
                    _state.value = EditorState.Rendering(it)
                }
                withContext(worker) { Gallery.saveVideo(ctx, out, name) }
            } finally {
                out.delete() // 갤러리에 복사했거나 실패·취소됨 — 어느 쪽이든 임시 파일은 필요 없다
            }
            _state.value = EditorState.Done(galleryUri) // 정리가 끝난 뒤에 완료 알림
        }
    }

    /** 처리 중 취소. 작업 스레드의 디코드도 다음 프레임에서 멈춘다. */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = EditorState.Idle
    }

    fun reset() = cancel()

    /** 작업 스레드에서 호출할 취소 확인 함수 — 이 코루틴(=job)이 취소됐는지 본다. */
    private suspend fun cancelledCheck(): () -> Boolean {
        val j = currentCoroutineContext()[Job]
        return { j?.isActive == false }
    }

    private fun launchSafely(block: suspend () -> Unit) {
        job?.cancel()
        job = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = EditorState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private companion object {
        const val MIN_TRIM_US = 1_000_000L
    }

    override fun onCleared() {
        worker.close()
    }
}
