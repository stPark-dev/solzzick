package com.zzick.core.store

import com.zzick.core.analysis.AnalysisResult
import com.zzick.core.quality.SourceInfo
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** 분석 결과 저장본: 앱의 Analysis와 같은 내용 + 분석에 쓴 탭 위치(여러 명일 때 고른 사람). */
class AnalysisRecord(
    val result: AnalysisResult,
    val srcW: Double,
    val srcH: Double,
    val fps: Double,
    val source: SourceInfo?,
    val tap: Pair<Double, Double>?,
)

/** 분석 결과를 기기에 저장하는 이진 형식. */
object AnalysisCodec {
    private const val MAGIC = 0x534A5A41 // "SJZA"

    /**
     * 분석 알고리즘이 바뀌어 결과가 달라지면 올린다 — 예전 저장본은 읽지 않고 다시 분석된다.
     * 2: 등반자 고르기(색 확인·사람 박스 주변 다시 찾기, 0.3.5) 변경
     * 3: 첫 포즈·오래 놓친 뒤 다시 잡기 확인, 크기 기준, 고르기 화면 프레임(0.4.0) 변경
     * 4: 색 못 잰 포즈 거절, 잘라서 다시 찾기 거리 제한, 이어 따라간 박스로 긴 공백 메우기(0.4.4) 변경
     */
    const val VERSION = 4

    /** 1시간 60fps(21.6만 프레임)의 넉넉한 배수 */
    private const val MAX_FRAMES = 1_000_000

    fun write(r: AnalysisRecord, out: OutputStream) {
        val d = DataOutputStream(out.buffered())
        d.writeInt(MAGIC)
        d.writeInt(VERSION)
        d.writeDouble(r.srcW)
        d.writeDouble(r.srcH)
        d.writeDouble(r.fps)
        d.writeOptional(r.tap) { writeDouble(it.first); writeDouble(it.second) }
        d.writeOptional(r.source) {
            writeInt(it.width); writeInt(it.height); writeDouble(it.fps); writeLong(it.bitrate); writeBoolean(it.hevc)
        }
        val n = r.result.ptsUs.size
        d.writeInt(n)
        for (i in 0 until n) {
            d.writeLong(r.result.ptsUs[i])
            d.writeInt(r.result.source[i])
            r.result.track[i].let { d.writeDouble(it[0]); d.writeDouble(it[1]) }
            r.result.body[i].forEach(d::writeDouble)
        }
        d.flush()
    }

    /** 다른 버전이거나 깨진 저장본이면 null. */
    fun read(input: InputStream): AnalysisRecord? = try {
        val d = DataInputStream(input.buffered())
        if (d.readInt() != MAGIC || d.readInt() != VERSION) null else {
            val srcW = d.readDouble()
            val srcH = d.readDouble()
            val fps = d.readDouble()
            val tap = d.readOptional { readDouble() to readDouble() }
            val source = d.readOptional { SourceInfo(readInt(), readInt(), readDouble(), readLong(), readBoolean()) }
            val n = d.readInt()
            require(n in 0..MAX_FRAMES) // 깨진 저장본으로 큰 메모리를 잡지 않게
            val pts = LongArray(n)
            val src = IntArray(n)
            val track = arrayOfNulls<DoubleArray>(n)
            val body = arrayOfNulls<DoubleArray>(n)
            for (i in 0 until n) {
                pts[i] = d.readLong()
                src[i] = d.readInt()
                track[i] = DoubleArray(2) { d.readDouble() }
                body[i] = DoubleArray(4) { d.readDouble() }
            }
            AnalysisRecord(AnalysisResult(track.requireNoNulls(), body.requireNoNulls(), src, pts), srcW, srcH, fps, source, tap)
        }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}

internal inline fun <T> DataOutputStream.writeOptional(v: T?, writeValue: DataOutputStream.(T) -> Unit) {
    writeBoolean(v != null)
    if (v != null) writeValue(v)
}

internal inline fun <T> DataInputStream.readOptional(readValue: DataInputStream.() -> T): T? = if (readBoolean()) readValue() else null
