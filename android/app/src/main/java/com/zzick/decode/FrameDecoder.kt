package com.zzick.decode

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 분석 패스용 디코더: 하드웨어 디코딩 → GPU에서 회전 보정 + 축소 → RGBA 비트맵.
 *
 * 프레임을 쌓지 않고 콜백으로 한 장씩 넘긴다(콜백의 bitmap은 재사용되므로 보관 금지).
 * GL 컨텍스트를 호출 스레드에 만들므로 [decode]는 한 스레드에서만 호출한다.
 */
class FrameDecoder(private val setSource: (MediaExtractor) -> Unit, targetHeight: Int = 640) {
    constructor(path: String, targetHeight: Int = 640) : this({ it.setDataSource(path) }, targetHeight)

    val rotation: Int
    /** 회전 적용 후(화면에 보이는) 원본 크기 */
    val srcWidth: Int
    val srcHeight: Int
    /** 콜백 비트맵 크기 */
    val width: Int
    val height: Int
    val durationUs: Long
    /** 컨테이너에 적힌 프레임레이트(없으면 30). 실제 값은 디코드한 pts로 다시 계산할 것. */
    val frameRate: Double
    /** 영상 코덱 MIME (예: video/hevc) */
    val mime: String
    private val trackIndex: Int
    private val format: MediaFormat

    init {
        val ex = MediaExtractor()
        try {
            setSource(ex)
            trackIndex = (0 until ex.trackCount).first {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/")
            }
            format = ex.getTrackFormat(trackIndex)
        } finally {
            ex.release()
        }
        val r = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
        rotation = ((r % 360) + 360) % 360
        val cw = format.getInteger(MediaFormat.KEY_WIDTH)
        val ch = format.getInteger(MediaFormat.KEY_HEIGHT)
        srcWidth = if (rotation % 180 == 0) cw else ch
        srcHeight = if (rotation % 180 == 0) ch else cw
        height = minOf(targetHeight, srcHeight)
        width = (srcWidth.toLong() * height / srcHeight).toInt() / 2 * 2
        durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        mime = format.getString(MediaFormat.KEY_MIME)!!
        frameRate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }
                .getOrElse { format.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }
        } else 30.0
    }

    /**
     * @param wantPixels false인 프레임은 GPU 읽기를 건너뛰고 이전 내용의 비트맵을 그대로 넘긴다(그 프레임의 픽셀은 쓰지 말 것).
     * @param stop 매 프레임 뒤 확인해 true면 디코드를 멈춘다.
     * @param frameBitmap 주면 프레임마다 이 비트맵(width × height, ARGB_8888)에 그린다 — 다른 스레드로 넘겨 쓰는 경우.
     *   없으면 비트맵 하나를 다시 쓴다(콜백 안에서만 유효).
     */
    fun decode(
        maxFrames: Int = Int.MAX_VALUE,
        wantPixels: (index: Int) -> Boolean = { true },
        stop: () -> Boolean = { false },
        frameBitmap: ((index: Int) -> Bitmap)? = null,
        onFrame: (index: Int, ptsUs: Long, frame: Bitmap) -> Unit,
    ): Int {
        // 모든 자원은 try 안에서 만들고 finally에서 하나씩 독립적으로 해제한다.
        // (코덱 오류 상태에서는 stop()이 던지므로, 한 해제 실패가 나머지 해제를 막지 않게)
        var gl: GlReader? = null
        var callbackThread: HandlerThread? = null
        var st: SurfaceTexture? = null
        var surface: Surface? = null
        var ex: MediaExtractor? = null
        var codec: MediaCodec? = null
        var bitmap: Bitmap? = null
        var count = 0
        try {
            gl = GlReader(width, height, rotation)
            callbackThread = HandlerThread("FrameDecoder").apply { start() }
            st = SurfaceTexture(gl.textureId)
            val lock = Object()
            var available = false
            st.setOnFrameAvailableListener({
                synchronized(lock) {
                    available = true
                    lock.notifyAll()
                }
            }, Handler(callbackThread.looper))
            surface = Surface(st)
            ex = MediaExtractor().apply {
                setSource(this)
                selectTrack(trackIndex)
            }
            val codecFormat = MediaFormat(format).apply { setInteger(MediaFormat.KEY_ROTATION, 0) } // 회전은 셰이더가 처리
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            if (frameBitmap == null) bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            codec.configure(codecFormat, surface, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var lastOutputAt = SystemClock.elapsedRealtime()
            while (true) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val size = ex.readSampleData(codec.getInputBuffer(inIdx)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx < 0) {
                    if (stop()) break // 멈추라면 출력을 기다리지 않는다
                    check(SystemClock.elapsedRealtime() - lastOutputAt < STALL_MS) { "디코더가 응답하지 않습니다 (frame $count)" }
                    continue
                }
                lastOutputAt = SystemClock.elapsedRealtime()
                val render = info.size != 0
                codec.releaseOutputBuffer(outIdx, render)
                if (render) {
                    synchronized(lock) {
                        val deadline = SystemClock.elapsedRealtime() + 2500
                        while (!available) {
                            val left = deadline - SystemClock.elapsedRealtime()
                            check(left > 0) { "디코더 프레임 대기 시간 초과 (frame $count)" }
                            lock.wait(left)
                        }
                        available = false
                    }
                    st.updateTexImage()
                    val target = frameBitmap?.invoke(count) ?: bitmap!!
                    if (wantPixels(count)) gl.draw(st, target)
                    onFrame(count++, info.presentationTimeUs, target)
                }
                if (count >= maxFrames || stop() || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex?.release() }
            runCatching { surface?.release() }
            runCatching { st?.release() }
            callbackThread?.quitSafely()
            runCatching { gl?.release() }
            bitmap?.recycle()
        }
        return count
    }

    private companion object {
        const val STALL_MS = 10_000L // 이 시간 동안 출력이 없으면 디코더가 멈춘 것으로 본다
    }
}

/** 오프스크린 EGL + 외부 텍스처를 회전·축소해 그리고 RGBA로 읽어오는 최소 GL 래퍼. */
private class GlReader(private val w: Int, private val h: Int, rotation: Int) {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    var textureId = 0
        private set
    private val quad: FloatBuffer
    private val pixels: ByteBuffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
    private val texMatrix = FloatArray(16)

    init {
        try {
            setUp()
        } catch (e: Throwable) {
            release() // 만들다 실패한 EGL 자원 정리
            throw e
        }
        quad = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer().put(quadVertices(rotation))
    }

    private fun setUp() {
        val ver = IntArray(2)
        check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "eglInitialize 실패" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(
            display,
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            ), 0, configs, 0, 1, n, 0,
        )
        check(n[0] > 0) { "EGL 설정을 찾지 못함" }
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        eglSurface = EGL14.eglCreatePbufferSurface(
            display, configs[0], intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0,
        )
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent 실패" }

        program = link(VERTEX, FRAGMENT)
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    /** 꼭짓점: clip(x,y) + tex(u,v). */
    private fun quadVertices(rotation: Int): FloatArray {
        // readPixels는 아래 행부터 읽으므로 clip y=-1 을 화면 '위'로 둔다.
        // 화면 좌표 (dx, dy[위=0]) ← 코딩 좌표 (cx, cy[위=0]) 를 회전별로 구하고 tex = (cx, 1 - cy).
        val verts = FloatArray(16)
        val corners = arrayOf(floatArrayOf(-1f, -1f), floatArrayOf(1f, -1f), floatArrayOf(-1f, 1f), floatArrayOf(1f, 1f))
        for ((i, c) in corners.withIndex()) {
            val dx = (c[0] + 1) / 2
            val dy = (c[1] + 1) / 2
            val (cx, cy) = when (rotation) {
                90 -> dy to 1 - dx
                180 -> 1 - dx to 1 - dy
                270 -> 1 - dy to dx
                else -> dx to dy
            }
            verts[i * 4] = c[0]
            verts[i * 4 + 1] = c[1]
            verts[i * 4 + 2] = cx
            verts[i * 4 + 3] = 1 - cy
        }
        return verts
    }

    fun draw(st: SurfaceTexture, out: Bitmap) {
        st.getTransformMatrix(texMatrix)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, texMatrix, 0)
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aTex = GLES20.glGetAttribLocation(program, "aTex")
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        pixels.rewind()
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        pixels.rewind()
        out.copyPixelsFromBuffer(pixels)
    }

    /** eglTerminate는 하지 않는다 — 같은 기본 디스플레이를 MediaPipe GPU 델리게이트도 쓴다. */
    fun release() {
        if (program != 0) GLES20.glDeleteProgram(program)
        if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        program = 0
        textureId = 0
        eglSurface = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "셰이더 컴파일 실패: ${GLES20.glGetShaderInfoLog(it)}" }
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, compile(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(it, compile(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(it)
        }
    }

    companion object {
        private const val VERTEX = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uTexMatrix;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = (uTexMatrix * aTex).xy;
            }
        """
        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() {
                gl_FragColor = texture2D(sTex, vTex);
            }
        """
    }
}
