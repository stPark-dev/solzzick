package com.zzick.pipeline

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import com.zzick.core.batch.BatchQueue
import com.zzick.core.batch.Stage
import com.zzick.core.batch.Work
import com.zzick.core.store.BatchQueueCodec
import com.zzick.core.trim.TrimWindow
import com.zzick.core.vcam.EditSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * 일괄 편집 처리기. 큐(BatchQueue)의 다음 일을 하나씩 실행하고, 일이 있는 동안 포그라운드 서비스를 띄워
 * 앱이 백그라운드로 가도 계속 처리한다. 모든 공개 함수는 메인 스레드에서 호출.
 * 목록은 stateFile에 저장해 앱이 강제 종료돼도 다시 켜면 이어서 처리하고, 분석 결과는 store에서 다시 붙인다.
 */
@UnstableApi
class BatchProcessor(
    context: Context,
    private val stateFile: File = File(context.applicationContext.filesDir, "batch_queue.bin"),
    private val store: AnalysisStore = AnalysisStore(context),
) {
    private val app = context.applicationContext
    private val pipeline = ClimbPipeline(app)
    private val overlayPrefs = OverlayPrefs(app)
    // 디코더가 GL 컨텍스트를 호출 스레드에 만들므로 무거운 작업은 전용 단일 스레드에서
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // 저장된 목록은 만들 때 바로 읽는다(작은 파일) — 복원 중에 넣은 영상도 번호가 이어지게(번호를 다시 매기면 돌려준 작업
    // 번호가 엉뚱한 작업을 가리키고, 진행 중인 결과가 다른 영상에 기록됐다). 분석 결과 다시 붙이기만 백그라운드에서.
    private val saved: BatchQueue<Analysis>? =
        runCatching { stateFile.inputStream().use { BatchQueueCodec.read<Analysis>(it) } }.getOrNull()
    private val _state = MutableStateFlow(saved ?: BatchQueue())
    val state: StateFlow<BatchQueue<Analysis>> = _state
    private val restoredDone = CompletableDeferred<Unit>() // 이게 끝나야 처리를 시작한다
    private val previews = mutableMapOf<Int, PeoplePreview>() // 선택이 필요한 항목의 첫 장면
    private var loop: Job? = null
    private var current: Pair<Int, Job>? = null

    init {
        scope.launch {
            restore()
            restoredDone.complete(Unit)
            if (_state.value.nextWork() != null) kick()
            // 복원이 끝난 뒤부터, 단계·구간·설정이 바뀔 때마다 저장(진행률은 빼고 — 너무 잦다)
            _state.map { q -> q.copy(items = q.items.map { it.copy(progress = 0f, analysis = null) }) }
                .distinctUntilChanged()
                .conflate()
                .collect { q -> withContext(Dispatchers.IO) { persist(q) } }
        }
    }

    /** 골라 넣은 영상들을 새 작업 하나로 넣고 그 작업 번호를 돌려준다(영상이 없으면 null). */
    fun add(uris: List<Uri>): Int? {
        if (uris.isEmpty()) return null
        // 앱을 다시 켠 뒤에도 영상을 읽을 수 있게(사진 선택기 주소는 허용, 공유로 받은 주소는 보통 거절 — 무시)
        uris.forEach { runCatching { app.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        _state.update { q ->
            // 새 작업의 공통 설정은 마지막 작업 그대로, 암장 이름·날짜 표시는 지난번 그대로
            val common = (q.jobs.lastOrNull()?.common ?: EditSettings()).copy(overlay = overlayPrefs.load())
            q.add(uris.map(Uri::toString), System.currentTimeMillis(), common)
        }
        kick()
        return _state.value.jobs.last().id
    }

    fun preview(id: Int): PeoplePreview? = previews[id]

    fun select(id: Int, tap: Pair<Double, Double>) {
        _state.update { it.onSelected(id, tap) }
        previews.remove(id)
        kick()
    }

    fun renderAll(jobId: Int) {
        _state.update { it.renderAll(jobId) }
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

    /** 작업을 목록에서 뺀다(진행 중인 일은 멈춤). 만든 영상은 갤러리에 남는다. */
    fun removeJob(jobId: Int) {
        _state.value.itemsOf(jobId).forEach { cancel(it.id); previews.remove(it.id) }
        _state.update { it.removeJob(jobId) }
    }

    fun rename(jobId: Int, name: String) = _state.update { it.rename(jobId, name) }

    fun setCommon(jobId: Int, settings: EditSettings) {
        val job = _state.value.jobs.firstOrNull { it.id == jobId } ?: return // 그사이 지운 작업
        if (settings.overlay != job.common.overlay) overlayPrefs.save(settings.overlay)
        _state.update { it.withCommon(jobId, settings) }
    }

    fun setItemSettings(id: Int, settings: EditSettings) {
        if (settings.overlay != _state.value.settingsFor(id).overlay) overlayPrefs.save(settings.overlay)
        _state.update { it.withItemSettings(id, settings) }
    }
    fun setWindow(id: Int, window: TrimWindow) = _state.update { it.withWindow(id, window) }

    private fun kick() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            restoredDone.await()
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
            if (work !is Work.Render) withContext(Dispatchers.IO) { pipeline.requireReadable(uri) }
            when (work) {
                is Work.Scan -> {
                    val saved = withContext(Dispatchers.IO) { store.load(uri) }
                    if (saved != null) {
                        _state.update { it.onAnalyzed(id, saved.analysis, saved.analysis.suggestTrim()) }
                        return
                    }
                    val preview = withContext(worker) { pipeline.findPeople(uri, cancelled) }
                    val people = preview?.boxes?.size ?: 0
                    if (people > 1) previews[id] = preview!!
                    _state.update { it.onScanned(id, people) }
                }
                is Work.Analyze -> {
                    val analysis = withContext(worker) {
                        pipeline.analyze(uri, work.tap, cancelled) { p -> if (!cancelled()) _state.update { it.onProgress(id, p) } }
                    }
                    withContext(Dispatchers.IO) { store.save(uri, analysis, work.tap) }
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
        val name = withContext(Dispatchers.IO) { pipeline.outputName(uri) } // solzzick_<원본 촬영 일시>.mp4
        val out = File(app.cacheDir, "render_${System.currentTimeMillis()}.mp4")
        val galleryUri = try {
            pipeline.render(uri, item.analysis!!, q.settingsFor(id).toParams(), out, item.window, overlay = q.settingsFor(id).overlay) { p ->
                _state.update { it.onProgress(id, p) }
            }
            withContext(worker) { Gallery.saveVideo(app, out, name) }
        } finally {
            out.delete()
        }
        _state.update { it.onRendered(id, galleryUri.toString()) }
    }

    /**
     * 저장본을 읽어 이어서 처리. 앱 시작 때 한 번, 디스크·영상 읽기는 백그라운드에서
     * (클라우드 영상은 여는 데 오래 걸릴 수 있다). 그사이 추가된 영상은 복원한 목록 뒤에 붙인다.
     */
    private suspend fun restore() {
        val from = saved ?: return
        // 분석 저장본 읽기는 느릴 수 있어(클라우드 영상) 백그라운드에서. 끊긴 일은 대기로 되돌리고 분석 결과를 다시 붙인다
        val restored = withContext(Dispatchers.IO) { from.restored { store.load(Uri.parse(it.source))?.analysis } }
        val byId = restored.items.associateBy { it.id }
        val before = from.items.associateBy { it.id }
        _state.update { cur ->
            // 그사이 사용자가 바꾼 영상(다시 시도·빼기 등)은 그대로 둔다
            cur.copy(items = cur.items.map { item -> byId[item.id]?.takeIf { before[item.id] == item } ?: item })
        }
    }

    private fun persist(q: BatchQueue<*>) {
        runCatching {
            if (q.items.isEmpty()) {
                stateFile.delete()
                return
            }
            val tmp = File(stateFile.path + ".tmp")
            java.io.FileOutputStream(tmp).use {
                BatchQueueCodec.write(q, it)
                it.fd.sync() // 전원이 꺼져도 빈 목록 파일이 남지 않게
            }
            check(tmp.renameTo(stateFile))
        }
    }

    companion object {
        @Volatile private var shared: BatchProcessor? = null

        /** 앱 전체에서 하나 — 화면이 바뀌거나 액티비티가 다시 만들어져도 처리 상태가 유지된다. */
        fun get(context: Context): BatchProcessor =
            shared ?: synchronized(this) { shared ?: BatchProcessor(context).also { shared = it } }
    }
}
