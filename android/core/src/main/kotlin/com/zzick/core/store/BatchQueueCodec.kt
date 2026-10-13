package com.zzick.core.store

import com.zzick.core.batch.BatchItem
import com.zzick.core.batch.BatchQueue
import com.zzick.core.batch.Stage
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
    private const val VERSION = 1

    fun write(q: BatchQueue<*>, out: OutputStream) {
        val d = DataOutputStream(out.buffered())
        d.writeInt(MAGIC)
        d.writeInt(VERSION)
        d.writeInt(q.nextId)
        d.writeSettings(q.common)
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
        }
        d.flush()
    }

    /** 다른 버전이거나 깨진 저장본이면 null. */
    fun <A> read(input: InputStream): BatchQueue<A>? = try {
        val d = DataInputStream(input.buffered())
        if (d.readInt() != MAGIC || d.readInt() != VERSION) null else {
            val nextId = d.readInt()
            val common = d.readSettings()
            val items = List(d.readInt()) {
                BatchItem<A>(
                    id = d.readInt(),
                    source = d.readUTF(),
                    stage = Stage.valueOf(d.readUTF()),
                    progress = d.readFloat(),
                    tap = d.readOptional { readDouble() to readDouble() },
                    window = d.readOptional { TrimWindow(readLong(), readLong()) },
                    settings = d.readOptional { readSettings() },
                    output = d.readOptional { readUTF() },
                    error = d.readOptional { readUTF() },
                )
            }
            BatchQueue(items, common, nextId)
        }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun DataOutputStream.writeSettings(s: EditSettings) {
        writeDouble(s.zoom); writeDouble(s.shake); writeUTF(s.aspect.name); writeBoolean(s.motionZoom)
    }

    private fun DataInputStream.readSettings() = EditSettings(readDouble(), readDouble(), AspectRatio.valueOf(readUTF()), readBoolean())
}
