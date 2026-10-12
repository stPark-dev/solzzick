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

class MpPoseEstimator(context: Context, numPoses: Int) : PoseEstimator<Bitmap>, AutoCloseable {
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

    override fun close() = landmarker.close()
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
