package com.zzick.pipeline

import android.content.Context
import android.media.MediaMetadataRetriever
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextureOverlay
import com.google.common.collect.ImmutableList
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.zzick.core.analysis.AnalysisResult
import com.zzick.core.analysis.Analyzer
import com.zzick.core.overlay.CaptureDate
import com.zzick.core.overlay.OverlayInfo
import com.zzick.core.quality.SourceInfo
import com.zzick.core.tracking.Box
import com.zzick.core.trim.Trim
import com.zzick.core.trim.TrimWindow
import com.zzick.core.vcam.CameraParams
import com.zzick.core.vcam.VirtualCamera
import com.zzick.decode.FrameDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

private fun Box.clamped() = Box(x0.coerceIn(0.0, 1.0), y0.coerceIn(0.0, 1.0), x1.coerceIn(0.0, 1.0), y1.coerceIn(0.0, 1.0))

/** 등반자 선택 화면용: 사람이 처음 보이는 프레임과 사람 박스(프레임 폭/높이 기준 0..1). */
class PeoplePreview(val frameIndex: Int, val bitmap: Bitmap, val boxes: List<Box>)

/** 분석 결과 + 렌더에 필요한 원본 정보. 파라미터만 바꿔 다시 렌더할 때 재사용한다. */
class Analysis(
    val result: AnalysisResult,
    val srcW: Double,
    val srcH: Double,
    val fps: Double,
    val source: SourceInfo? = null, // 화질 안내용
) {
    val firstPtsUs get() = result.ptsUs.first()
    val lastPtsUs get() = result.ptsUs.last()

    /** 등반자가 보이는 구간 + 여유 — 사용자가 손잡이로 조정하기 전 기본값. */
    fun suggestTrim(): TrimWindow = Trim.suggest(result.source, result.ptsUs)
}

/** MVP 파이프라인: 분석(디코드 + 포즈/검출 + 트랙 합치기) → 가상 카메라 경로 → Media3 렌더. */
@UnstableApi
class ClimbPipeline(private val context: Context) {
    fun decoder(uri: Uri, targetHeight: Int = 640) =
        FrameDecoder({ it.setDataSource(context, uri, null) }, targetHeight)

    /**
     * 영상을 열 수 있는지 미리 확인 — 디코더 오류("Failed to instantiate extractor") 대신 알아볼 수 있는 이유로 실패.
     * 공유로 받은 영상은 앱을 다시 켜면 읽기 권한이 사라져 있을 수 있다.
     */
    fun requireReadable(uri: Uri) {
        val ok = runCatching { context.contentResolver.openFileDescriptor(uri, "r")!!.close() }.isSuccess
        check(ok) { "영상을 열 수 없어요. 다시 골라 주세요(공유로 받은 영상이면 다시 공유해 주세요)" }
    }

    /** 원본을 찍은 날짜(이 기기 시간대). 영상에 기록이 없으면 null. */
    fun captureDate(uri: Uri): LocalDate? = runCatching {
        val raw = MediaMetadataRetriever().run {
            try {
                setDataSource(context, uri)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            } finally {
                release()
            }
        }
        CaptureDate.parse(raw, ZoneId.systemDefault())
    }.getOrNull()

    /** 화질 안내용 원본 정보. 비트레이트는 파일 크기 / 길이(오디오 포함), 모르면 0. */
    fun sourceInfo(uri: Uri): SourceInfo = sourceInfo(uri, decoder(uri))

    private fun sourceInfo(uri: Uri, dec: FrameDecoder): SourceInfo {
        val size = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        val bitrate = if (size > 0 && dec.durationUs > 0) size * 8_000_000 / dec.durationUs else 0L
        return SourceInfo(dec.srcWidth, dec.srcHeight, dec.frameRate, bitrate, hevc = dec.mime == MimeTypes.VIDEO_H265)
    }

    /**
     * 0.5초 간격으로 훑어 사람이 처음 보이는 프레임을 찾는다. 끝까지 없으면 null. 호출 스레드에서 실행.
     * @throws CancellationException isCancelled가 true가 되면 즉시 멈춘다.
     */
    fun findPeople(uri: Uri, isCancelled: () -> Boolean = { false }): PeoplePreview? {
        val dec = decoder(uri)
        val step = maxOf(1, (dec.frameRate / 2).roundToInt())
        var found: PeoplePreview? = null
        MpPersonDetector(context).use { det ->
            dec.decode(wantPixels = { it % step == 0 }, stop = { found != null || isCancelled() }) { i, _, bmp ->
                if (i % step != 0) return@decode
                val boxes = det.detect(bmp).map { it.clamped() }
                if (boxes.isNotEmpty()) found = PeoplePreview(i, bmp.copy(bmp.config!!, false), boxes)
            }
        }
        if (isCancelled()) throw CancellationException("사용자 취소")
        return found
    }

    /**
     * 호출 스레드에서 끝까지 실행된다(GL 컨텍스트를 이 스레드에 만든다). UI 스레드에서 부르지 말 것.
     * @param tap 사용자가 고른 등반자 위치(프레임 폭/높이 기준 0..1). 주면 여러 명 중 그 사람을 따라간다.
     * @throws CancellationException isCancelled가 true가 되면 즉시 멈춘다(프레임마다 확인).
     */
    fun analyze(
        uri: Uri,
        tap: Pair<Double, Double>?,
        isCancelled: () -> Boolean = { false },
        opticalFlow: Boolean = true, // 포즈 공백을 광학 흐름으로 메우기(끄는 건 비교 측정용)
        onProgress: (Float) -> Unit,
    ): Analysis {
        val dec = decoder(uri)
        val stride = Analyzer.strideFor(dec.frameRate)
        val expected = (dec.durationUs / 1e6 * dec.frameRate).coerceAtLeast(1.0)
        MpPoseEstimator(context, numPoses = if (tap != null) 2 else 1).use { pose ->
            MpPersonDetector(context).use { det ->
                val analyzer = Analyzer(
                    pose, det, dec.srcWidth.toDouble(), dec.srcHeight.toDouble(), dec.frameRate, stride,
                    tap = tap, appearance = BitmapAppearance(), gray = if (opticalFlow) BitmapGray() else null,
                )
                var lastPts = 0L
                var firstPts = 0L
                // 포즈 추정 프레임 + 광학 흐름으로 따라가는 공백 구간만 픽셀을 읽는다
                val n = dec.decode(wantPixels = analyzer::needsPixels, stop = isCancelled) { i, pts, bmp ->
                    if (i == 0) firstPts = pts
                    lastPts = pts
                    analyzer.onFrame(i, pts, bmp)
                    if (i % 30 == 0) onProgress((i / expected).toFloat().coerceAtMost(0.99f))
                }
                if (isCancelled()) throw CancellationException("사용자 취소")
                val fps = if (n > 1 && lastPts > firstPts) (n - 1) * 1e6 / (lastPts - firstPts) else dec.frameRate
                val result = analyzer.finish(fps)
                onProgress(1f)
                return Analysis(result, dec.srcWidth.toDouble(), dec.srcHeight.toDouble(), fps, sourceInfo(uri, dec).copy(fps = fps))
            }
        }
    }

    fun solvePath(analysis: Analysis, params: CameraParams): Array<DoubleArray> = VirtualCamera.solvePath(
        analysis.result.track, analysis.fps, analysis.srcW, analysis.srcH, params, analysis.result.body,
    )

    /**
     * 원본 해상도에서 크롭해 outHeight × (outHeight·aspect) 로 인코딩. 오디오는 원본 유지. trim이 있으면 그 구간만.
     * @param videoBitrate 출력 영상 비트레이트(bps). null이면 Media3 기본값.
     */
    suspend fun render(
        uri: Uri,
        analysis: Analysis,
        params: CameraParams,
        out: File,
        trim: TrimWindow? = null,
        videoBitrate: Int? = null,
        overlay: OverlayInfo = OverlayInfo(), // 영상 구석 표시(암장·날짜·난이도)
        onProgress: (Float) -> Unit,
    ) {
        val path = withContext(Dispatchers.Default) { solvePath(analysis, params) }
        val (outW, outH) = VirtualCamera.outputSize(params)
        val mark = withContext(Dispatchers.IO) {
            OverlayImage.render(overlay.grade, overlay.words(if (overlay.showDate) captureDate(uri) else null), outW, outH)
        }
        val window = trim ?: TrimWindow(analysis.firstPtsUs, analysis.lastPtsUs)
        val effect = CropEffect(path, analysis.result.ptsUs, window.startUs, analysis.srcW, analysis.srcH, outW, outH)
        val clip = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs((window.startUs - analysis.firstPtsUs) / 1000)
            .setEndPositionMs((window.endUs - analysis.firstPtsUs) / 1000 + 1) // 마지막 프레임 포함
            .build()
        val media = MediaItem.Builder().setUri(uri).setClippingConfiguration(clip).build()
        val item = EditedMediaItem.Builder(media)
            .setEffects(Effects(emptyList(), listOfNotNull(effect, mark?.let { OverlayEffect(ImmutableList.of<TextureOverlay>(BitmapOverlay.createStaticBitmapOverlay(it))) })))
            .build()
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val handler = Handler(Looper.getMainLooper())
                val progress = ProgressHolder()
                lateinit var poll: Runnable
                val builder = Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264)
                if (videoBitrate != null) {
                    val settings = VideoEncoderSettings.Builder().setBitrate(videoBitrate).build()
                    builder.setEncoderFactory(DefaultEncoderFactory.Builder(context).setRequestedVideoEncoderSettings(settings).build())
                }
                val transformer = builder
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            handler.removeCallbacks(poll)
                            onProgress(1f)
                            cont.resume(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            handler.removeCallbacks(poll)
                            cont.resumeWithException(exportException)
                        }
                    })
                    .build()
                poll = Runnable {
                    if (transformer.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(progress.progress / 100f)
                    }
                    handler.postDelayed(poll, 250)
                }
                cont.invokeOnCancellation {
                    handler.removeCallbacks(poll)
                    transformer.cancel()
                }
                transformer.start(item, out.path)
                handler.post(poll)
            }
        }
    }
}
