package com.zzick.pipeline

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import com.zzick.core.batch.BatchQueue
import com.zzick.core.batch.Stage
import com.zzick.core.batch.Work
import com.zzick.core.trim.TrimWindow
import com.zzick.core.vcam.EditSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * 일괄 편집 처리기. 큐(BatchQueue)의 다음 일을 하나씩 실행하고, 일이 있는 동안 포그라운드 서비스를 띄워
 * 앱이 백그라운드로 가도 계속 처리한다. 모든 공개 함수는 메인 스레드에서 호출.
 */
@UnstableApi
class BatchProcessor(context: Context) {
    private val app = context.applicationContext
    private val pipeline = ClimbPipeline(app)
    // 디코더가 GL 컨텍스트를 호출 스레드에 만들므로 무거운 작업은 전용 단일 스레드에서
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(BatchQueue<Analysis>())
    val state: StateFlow<BatchQueue<Analysis>> = _state
    private val previews = mutableMapOf<Int, PeoplePreview>() // 선택이 필요한 항목의 첫 장면
    private var loop: Job? = null
    private var current: Pair<Int, Job>? = null

    fun add(uris: List<Uri>) {
        _state.update { q -> q.add(uris.map(Uri::toString)) }
        kick()
    }

    fun preview(id: Int): PeoplePreview? = previews[id]

    fun select(id: Int, tap: Pair<Double, Double>) {
        _state.update { it.onSelected(id, tap) }
        previews.remove(id)
        kick()
    }

    fun renderAll() {
        _state.update { it.renderAll() }
        kick()
    }

    fun cancel(id: Int) {
        _state.update { it.cancel(id) }
        current?.takeIf { it.first == id }?.second?.cancel()
    }

    fun retry(id: Int) {
        _state.update { it.retry(id) }
        kick()
    }

    fun remove(id: Int) {
        cancel(id)
        previews.remove(id)
        _state.update { it.remove(id) }
    }

    fun setCommon(settings: EditSettings) = _state.update { it.withCommon(settings) }
    fun setItemSettings(id: Int, settings: EditSettings) = _state.update { it.withItemSettings(id, settings) }
    fun setWindow(id: Int, window: TrimWindow) = _state.update { it.withWindow(id, window) }

    private fun kick() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            BatchService.start(app)
            try {
                while (true) {
                    val work = _state.value.nextWork() ?: break
                    val id = when (work) {
                        is Work.Scan -> work.id
                        is Work.Analyze -> work.id
                        is Work.Render -> work.id
                    }
                    _state.update { it.onStarted(id) }
                    val job = launch { run(work, id) }
                    current = id to job
                    job.join()
                    current = null
                }
            } finally {
                BatchService.stop(app)
            }
        }
    }

    private suspend fun run(work: Work, id: Int) {
        val job = kotlinx.coroutines.currentCoroutineContext()[Job]
        val cancelled = { job?.isActive == false }
        val uri = Uri.parse(_state.value.item(id).source)
        try {
            when (work) {
                is Work.Scan -> {
                    val preview = withContext(worker) { pipeline.findPeople(uri, cancelled) }
                    val people = preview?.boxes?.size ?: 0
                    if (people > 1) previews[id] = preview!!
                    _state.update { it.onScanned(id, people) }
                }
                is Work.Analyze -> {
                    val analysis = withContext(worker) {
                        pipeline.analyze(uri, work.tap, cancelled) { p -> if (!cancelled()) _state.update { it.onProgress(id, p) } }
                    }
                    _state.update { it.onAnalyzed(id, analysis, analysis.suggestTrim()) }
                }
                is Work.Render -> render(uri, id)
            }
        } catch (e: CancellationException) {
            // 이 항목만 취소됨 — 큐에는 cancel()에서 이미 반영. 다음 일로 넘어간다.
        } catch (e: Exception) {
            _state.update { it.onFailed(id, e.message ?: e.javaClass.simpleName) }
        }
    }

    private suspend fun render(uri: Uri, id: Int) {
        val q = _state.value
        val item = q.item(id)
        val name = "zzick_${System.currentTimeMillis()}.mp4"
        val out = File(app.cacheDir, name)
        val galleryUri = try {
            pipeline.render(uri, item.analysis!!, q.settingsFor(id).toParams(), out, item.window) { p ->
                _state.update { it.onProgress(id, p) }
            }
            withContext(worker) { Gallery.saveVideo(app, out, name) }
        } finally {
            out.delete()
        }
        _state.update { it.onRendered(id, galleryUri.toString()) }
    }

    companion object {
        @Volatile private var shared: BatchProcessor? = null

        /** 앱 전체에서 하나 — 화면이 바뀌거나 액티비티가 다시 만들어져도 처리 상태가 유지된다. */
        fun get(context: Context): BatchProcessor =
            shared ?: synchronized(this) { shared ?: BatchProcessor(context).also { shared = it } }
    }
}
