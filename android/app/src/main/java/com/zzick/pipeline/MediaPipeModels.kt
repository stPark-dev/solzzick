package com.zzick.pipeline

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.zzick.core.analysis.PersonDetector
import com.zzick.core.analysis.PoseCandidate
import com.zzick.core.analysis.PoseEstimator
import com.zzick.core.tracking.Box

/** GPU 우선, 실패하면 CPU로 만든다(일부 기기 GPU 델리게이트 미지원 대비). */
private fun <T> withDelegateFallback(create: (Delegate) -> T): T =
    runCatching { create(Delegate.GPU) }.getOrElse { create(Delegate.CPU) }

class MpPoseEstimator(private val context: Context, numPoses: Int) : PoseEstimator<Bitmap>, AutoCloseable {
    private val landmarker = withDelegateFallback { delegate ->
        PoseLandmarker.createFromOptions(
            context,
            PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_full.task").setDelegate(delegate).build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumPoses(numPoses)
                .build(),
        )
    }

    override fun estimate(frame: Bitmap, timestampMs: Long): List<PoseCandidate> =
        landmarker.detectForVideo(BitmapImageBuilder(frame).build(), timestampMs).landmarks().map { lms ->
            PoseCandidate(
                FloatArray(lms.size) { lms[it].x() },
                FloatArray(lms.size) { lms[it].y() },
                FloatArray(lms.size) { lms[it].visibility().orElse(0f) },
            )
        }

    /** 잘라낸 영역용(프레임마다 독립 — 앞 프레임 추적에 끌려가지 않게 IMAGE 모드). 필요할 때만 만든다. */
    private val cropLandmarker by lazy {
        withDelegateFallback { delegate ->
            PoseLandmarker.createFromOptions(
                context,
                PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_full.task").setDelegate(delegate).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumPoses(1)
                    .build(),
            )
        }
    }
    private var cropUsed = false

    override fun estimateCrop(frame: Bitmap, box: Box): List<PoseCandidate> {
        // 사람 박스보다 넉넉한 정사각형(팔다리가 박스 밖으로 나가도 잡히게)
        val side = maxOf((box.x1 - box.x0) * frame.width, (box.y1 - box.y0) * frame.height) * 1.4
        val x0 = (box.cx * frame.width - side / 2).toInt().coerceIn(0, frame.width - 1)
        val y0 = (box.cy * frame.height - side / 2).toInt().coerceIn(0, frame.height - 1)
        val x1 = (box.cx * frame.width + side / 2).toInt().coerceIn(x0 + 1, frame.width)
        val y1 = (box.cy * frame.height + side / 2).toInt().coerceIn(y0 + 1, frame.height)
        val crop = Bitmap.createBitmap(frame, x0, y0, x1 - x0, y1 - y0)
        cropUsed = true
        val sx = (x1 - x0).toFloat() / frame.width
        val sy = (y1 - y0).toFloat() / frame.height
        return cropLandmarker.detect(BitmapImageBuilder(crop).build()).landmarks().map { lms ->
            PoseCandidate(
                FloatArray(lms.size) { x0.toFloat() / frame.width + lms[it].x() * sx },
                FloatArray(lms.size) { y0.toFloat() / frame.height + lms[it].y() * sy },
                FloatArray(lms.size) { lms[it].visibility().orElse(0f) },
            )
        }.also { if (crop !== frame) crop.recycle() }
    }

    override fun close() {
        landmarker.close()
        if (cropUsed) cropLandmarker.close()
    }
}

class MpPersonDetector(context: Context) : PersonDetector<Bitmap>, AutoCloseable {
    // GPU 경로는 category allowlist를 쓰면 네이티브 abort → person은 여기서 거른다
    private val detector = withDelegateFallback { delegate ->
        ObjectDetector.createFromOptions(
            context,
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite2.tflite").setDelegate(delegate).build())
                .setScoreThreshold(0.2f)
                .setMaxResults(50) // person 필터 전에 잘리지 않도록 넉넉히(사람이 아닌 물체가 상위를 차지할 수 있음)
                .build(),
        )
    }

    override fun detect(frame: Bitmap): List<Box> {
        val w = frame.width.toDouble()
        val h = frame.height.toDouble()
        return detector.detect(BitmapImageBuilder(frame).build()).detections()
            .filter { d -> d.categories().any { it.categoryName() == "person" } }
            .map { d ->
                val b = d.boundingBox()
                Box(b.left / w, b.top / h, b.right / w, b.bottom / h)
            }
    }

    override fun close() = detector.close()
}
