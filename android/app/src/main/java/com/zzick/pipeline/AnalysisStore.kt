package com.zzick.pipeline

import android.content.Context
import android.net.Uri
import com.zzick.core.store.AnalysisCodec
import com.zzick.core.store.AnalysisRecord
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** 저장해 둔 분석 결과와, 그때 따라간 사람(탭 위치, 한 명이면 null). */
class StoredAnalysis(val analysis: Analysis, val tap: Pair<Double, Double>?)

/**
 * 분석 결과를 기기에 저장해 같은 영상을 다시 열면 분석을 건너뛴다.
 * 같은 영상인지는 주소가 아니라 내용(파일 크기 + 앞뒤 64KB)으로 판단한다 — 갤러리에서 고르든 공유로 받든
 * 같은 영상이면 저장본을 쓰고, 영상이 바뀌면(다시 저장·편집) 저절로 다시 분석된다.
 * 최근 maxEntries개만 남긴다. 디스크 입출력이 있으니 메인 스레드에서 부르지 말 것.
 */
class AnalysisStore(
    context: Context,
    private val dir: File = File(context.filesDir, "analysis"),
    private val maxEntries: Int = 50,
) {
    private val resolver = context.applicationContext.contentResolver

    fun load(uri: Uri): StoredAnalysis? {
        val f = File(dir, (key(uri) ?: return null) + EXT)
        if (!f.exists()) return null
        val r = runCatching { f.inputStream().use(AnalysisCodec::read) }.getOrNull() ?: return null
        f.setLastModified(System.currentTimeMillis()) // 최근에 쓴 것으로
        return StoredAnalysis(Analysis(r.result, r.srcW, r.srcH, r.fps, r.source), r.tap)
    }

    /** 저장 실패는 무시한다(다음에 다시 분석될 뿐). */
    fun save(uri: Uri, analysis: Analysis, tap: Pair<Double, Double>?) {
        val k = key(uri) ?: return
        dir.mkdirs()
        // 편집 화면과 일괄 편집이 같은 영상을 동시에 저장할 수 있으니 임시 파일은 저장마다 따로
        val tmp = runCatching { File.createTempFile(k, TMP, dir) }.getOrNull() ?: return
        try {
            FileOutputStream(tmp).use {
                AnalysisCodec.write(AnalysisRecord(analysis.result, analysis.srcW, analysis.srcH, analysis.fps, analysis.source, tap), it)
                it.fd.sync() // 전원이 꺼져도 빈 파일이 남지 않게
            }
            check(tmp.renameTo(File(dir, k + EXT)))
            trim()
        } catch (e: Exception) {
            // 다음에 다시 분석될 뿐
        } finally {
            tmp.delete()
        }
    }

    fun count(): Int = entries().size

    fun clear() {
        dir.deleteRecursively()
    }

    private fun entries() = dir.listFiles { f -> f.name.endsWith(EXT) }.orEmpty().toList()

    private fun trim() {
        entries().sortedByDescending { it.lastModified() }.drop(maxEntries).forEach { it.delete() }
    }

    /** 영상 내용 지문. 읽을 수 없으면 null(저장·재사용하지 않음). */
    private fun key(uri: Uri): String? = runCatching {
        resolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            val size = pfd.statSize
            require(size > 0)
            val md = MessageDigest.getInstance("SHA-256")
            md.update(ByteBuffer.allocate(8).putLong(size).array())
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                val buf = ByteBuffer.allocate(CHUNK)
                for (pos in listOf(0L, maxOf(0L, size - CHUNK))) {
                    buf.clear()
                    ch.position(pos)
                    while (buf.hasRemaining() && ch.read(buf) > 0) Unit
                    md.update(buf.array(), 0, buf.position())
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
    }.getOrNull()

    private companion object {
        const val EXT = ".sja"
        const val TMP = ".tmp"
        const val CHUNK = 64 * 1024
    }
}
