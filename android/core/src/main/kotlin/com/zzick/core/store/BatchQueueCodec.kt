package com.zzick.core.store

import com.zzick.core.batch.BatchItem
import com.zzick.core.batch.BatchJob
import com.zzick.core.batch.BatchQueue
import com.zzick.core.batch.Stage
import com.zzick.core.overlay.Corner
import com.zzick.core.overlay.Grade
import com.zzick.core.overlay.OverlayInfo
import com.zzick.core.trim.TrimWindow
import com.zzick.core.vcam.AspectRatio
import com.zzick.core.vcam.EditSettings
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** 일괄 편집 목록을 기기에 저장하는 이진 형식. 분석 결과는 넣지 않는다(분석 저장본에서 다시 붙인다 — BatchQueue.restored). */
object BatchQueueCodec {
    private const val MAGIC = 0x534A5A42 // "SJZB"
    /**
     * 2: 설정에 영상 구석 표시(OverlayInfo) 추가. 1도 읽는다(표시 없음).
     * 3: 작업(BatchJob) — 공통 설정이 작업마다. 1·2는 목록 전체를 "이전 작업" 하나로 읽는다.
     * 4: 표시 구석(Corner). 3 이하는 왼쪽 아래.
     */
    private const val VERSION = 4
    const val PREVIOUS_JOB_NAME = "이전 작업"

    fun write(q: BatchQueue<*>, out: OutputStream) {
        val d = DataOutputStream(out.buffered())
        d.writeInt(MAGIC)
        d.writeInt(VERSION)
        d.writeInt(q.nextId)
        d.writeInt(q.nextJobId)
        d.writeInt(q.jobs.size)
        for (j in q.jobs) {
            d.writeInt(j.id)
            d.writeLong(j.createdAtMs)
            d.writeOptional(j.name) { n -> writeUTF(n) }
            d.writeSettings(j.common)
        }
        d.writeInt(q.items.size)
        for (it in q.items) {
            d.writeInt(it.id)
            d.writeUTF(it.source)
            d.writeUTF(it.stage.name)
            d.writeFloat(it.progress)
            d.writeOptional(it.tap) { t -> writeDouble(t.first); writeDouble(t.second) }
            d.writeOptional(it.window) { w -> writeLong(w.startUs); writeLong(w.endUs) }
            d.writeOptional(it.settings) { s -> writeSettings(s) }
            d.writeOptional(it.output) { o -> writeUTF(o) }
            d.writeOptional(it.error) { e -> writeUTF(e) }
            d.writeInt(it.jobId)
        }
        d.flush()
    }

    /** 다른 버전이거나 깨진 저장본이면 null. */
    fun <A> read(input: InputStream): BatchQueue<A>? = try {
        val d = DataInputStream(input.buffered())
        val version = if (d.readInt() == MAGIC) d.readInt() else -1
        if (version !in 1..VERSION) null else {
            val nextId = d.readInt()
            val nextJobId = if (version >= 3) d.readInt() else 2
            val jobs = if (version >= 3) {
                List(d.readInt()) { BatchJob(d.readInt(), d.readLong(), d.readOptional { readUTF() }, d.readSettings(version)) }
            } else {
                listOf(BatchJob(1, 0L, PREVIOUS_JOB_NAME, d.readSettings(version)))
            }
            val items = List(d.readInt()) {
                BatchItem<A>(
                    id = d.readInt(),
                    source = d.readUTF(),
                    stage = Stage.valueOf(d.readUTF()),
                    progress = d.readFloat(),
                    tap = d.readOptional { readDouble() to readDouble() },
                    window = d.readOptional { TrimWindow(readLong(), readLong()) },
                    settings = d.readOptional { readSettings(version) },
                    output = d.readOptional { readUTF() },
                    error = d.readOptional { readUTF() },
                    jobId = if (version >= 3) d.readInt() else 1,
                )
            }
            // 작업 없는 영상(깨진 저장본)은 빼고, 영상 없는 작업도 뺀다
            val kept = items.filter { i -> jobs.any { it.id == i.jobId } }
            BatchQueue(kept, jobs.filter { j -> kept.any { it.jobId == j.id } }, nextId, nextJobId)
        }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun DataOutputStream.writeSettings(s: EditSettings) {
        writeDouble(s.zoom); writeDouble(s.shake); writeUTF(s.aspect.name); writeBoolean(s.motionZoom)
        writeUTF(s.overlay.gym); writeBoolean(s.overlay.showDate); writeOptional(s.overlay.grade) { writeUTF(it.name) }
        writeUTF(s.overlay.corner.name)
    }

    private fun DataInputStream.readSettings(version: Int): EditSettings {
        val base = EditSettings(readDouble(), readDouble(), AspectRatio.valueOf(readUTF()), readBoolean())
        if (version < 2) return base
        val overlay = OverlayInfo(readUTF(), readBoolean(), readOptional { Grade.valueOf(readUTF()) })
        return base.copy(overlay = if (version < 4) overlay else overlay.copy(corner = Corner.valueOf(readUTF())))
    }
}
