package com.zzick.decode

import android.graphics.Bitmap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 디코딩을 별도 스레드에서 미리(최대 depth프레임) 해 두고 호출 스레드에서 차례로 넘긴다 — 추론(포즈·검출)과 겹쳐
 * 분석 시간을 줄인다(40초 1440² 영상에서 디코딩 7~9초). 어느 프레임의 픽셀이 필요할지(광학 흐름) 미리 알 수 없어
 * 모든 프레임을 읽는다 — 늘어난 읽기 비용은 디코딩 스레드가 진다. 디코더의 GL 컨텍스트는 그 스레드에 만들어진다.
 *
 * onFrame의 비트맵은 콜백 안에서만 유효하다 — 돌아오면 다음 프레임에 다시 쓰인다(붙잡아 두지 말 것).
 * 멈춘 경우(stop)에는 디코딩 스레드의 오류를 던지지 않는다 — 부르는 쪽이 취소로 처리하게.
 *
 * @param stop 매 프레임 뒤 확인해 true면 멈춘다(디코딩 스레드도 곧 멈춘다).
 * @return 넘긴 프레임 수
 */
fun FrameDecoder.decodeAhead(
    stop: () -> Boolean = { false },
    depth: Int = 4,
    onFrame: (index: Int, ptsUs: Long, frame: Bitmap) -> Unit,
): Int {
    val pool = List(depth + 2) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
    val free = ArrayBlockingQueue<Bitmap>(pool.size).apply { addAll(pool) }
    val ready = ArrayBlockingQueue<Frame>(pool.size + 1) // +1: 끝 표시(END) 자리가 늘 남아 디코딩 스레드가 막히지 않는다
    val halt = AtomicBoolean(false)
    var failure: Throwable? = null
    val spare = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) // 멈추는 중에 그릴 곳

    fun <T> waitFor(get: () -> T?): T? {
        while (!halt.get()) get()?.let { return it }
        return null
    }

    val producer = thread(name = "DecodeAhead") {
        try {
            decode(
                wantPixels = { true },
                stop = { halt.get() },
                frameBitmap = { waitFor { free.poll(50, TimeUnit.MILLISECONDS) } ?: spare },
            ) { i, pts, bmp ->
                if (bmp !== spare) waitFor { Frame(i, pts, bmp).takeIf { ready.offer(it, 50, TimeUnit.MILLISECONDS) } }
            }
        } catch (e: Throwable) {
            failure = e
        } finally {
            while (!ready.offer(Frame.END, 50, TimeUnit.MILLISECONDS)) if (halt.get()) break
        }
    }

    var count = 0
    var stopped = false
    try {
        while (true) {
            val f = ready.take()
            if (f === Frame.END) break
            onFrame(f.index, f.ptsUs, f.bitmap!!)
            count++
            free.put(f.bitmap)
            if (stop()) {
                stopped = true
                break
            }
        }
    } finally {
        halt.set(true)
        producer.join()
        (pool + spare).forEach(Bitmap::recycle)
    }
    if (!stopped) failure?.let { throw it }
    return count
}

private class Frame(val index: Int, val ptsUs: Long, val bitmap: Bitmap?) {
    companion object {
        val END = Frame(-1, 0, null)
    }
}
