package com.ts10.inspector.vision

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Receives Grabadora HD's GPU/private output without asking ImageReader for CPU planes.
 * The external texture is reduced to 320x180 in an FBO and only that small result is read.
 */
internal class PrivateSurfaceGlFrameSource(
    sourceWidth: Int,
    sourceHeight: Int,
    private val handler: Handler,
    private val onFrame: (PrivateGlFrame) -> Unit,
    private val onError: (Throwable) -> Unit,
) : AutoCloseable {
    val surface: Surface

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var externalTexture = 0
    private var outputTexture = 0
    private var framebuffer = 0
    private var program = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val output = ByteBuffer.allocateDirect(OUTPUT_WIDTH * OUTPUT_HEIGHT * 4)
        .order(ByteOrder.nativeOrder())
    private val textureMatrix = FloatArray(16)
    private var closed = false

    init {
        initialiseEgl()
        externalTexture = createExternalTexture()
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        createOutputFramebuffer()
        val texture = SurfaceTexture(externalTexture).also {
            it.setDefaultBufferSize(sourceWidth, sourceHeight)
            it.setOnFrameAvailableListener({ receiveLatestFrame() }, handler)
        }
        surfaceTexture = texture
        surface = Surface(texture)
    }

    private fun initialiseEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val versions = IntArray(2)
        check(EGL14.eglInitialize(display, versions, 0, versions, 1)) { "eglInitialize failed" }
        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "eglChooseConfig failed"
        }
        val config = requireNotNull(configs[0])
        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        eglSurface = EGL14.eglCreatePbufferSurface(
            display,
            config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0,
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
        makeCurrent()
    }

    private fun receiveLatestFrame() {
        if (closed) return
        val started = SystemClock.elapsedRealtimeNanos()
        runCatching {
            makeCurrent()
            val texture = surfaceTexture ?: return
            texture.updateTexImage()
            texture.getTransformMatrix(textureMatrix)
            renderToSmallFramebuffer()
            output.position(0)
            GLES20.glReadPixels(
                0,
                0,
                OUTPUT_WIDTH,
                OUTPUT_HEIGHT,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                output,
            )
            checkGl("glReadPixels")
            output.position(0)
            output.limit(output.capacity())
            onFrame(
                PrivateGlFrame(
                    rgba = output,
                    width = OUTPUT_WIDTH,
                    height = OUTPUT_HEIGHT,
                    capturedElapsedRealtime = SystemClock.elapsedRealtime(),
                    gpuReadbackNanos = SystemClock.elapsedRealtimeNanos() - started,
                ),
            )
        }.onFailure(onError)
    }

    private fun renderToSmallFramebuffer() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glViewport(0, 0, OUTPUT_WIDTH, OUTPUT_HEIGHT)
        GLES20.glUseProgram(program)
        val positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        val textureLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
        val matrixLocation = GLES20.glGetUniformLocation(program, "uTexMatrix")
        val samplerLocation = GLES20.glGetUniformLocation(program, "uTexture")
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, VERTICES)
        GLES20.glEnableVertexAttribArray(textureLocation)
        GLES20.glVertexAttribPointer(textureLocation, 2, GLES20.GL_FLOAT, false, 0, TEX_COORDS)
        GLES20.glUniformMatrix4fv(matrixLocation, 1, false, textureMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexture)
        GLES20.glUniform1i(samplerLocation, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGl("draw PRIVATE")
        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glDisableVertexAttribArray(textureLocation)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    private fun createExternalTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        checkGl("external texture")
        return ids[0]
    }

    private fun createOutputFramebuffer() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        outputTexture = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outputTexture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            OUTPUT_WIDTH,
            OUTPUT_HEIGHT,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null,
        )
        val framebuffers = IntArray(1)
        GLES20.glGenFramebuffers(1, framebuffers, 0)
        framebuffer = framebuffers[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            outputTexture,
            0,
        )
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            "Incomplete PRIVATE framebuffer"
        }
        checkGl("small framebuffer")
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val result = GLES20.glCreateProgram()
        GLES20.glAttachShader(result, vertex)
        GLES20.glAttachShader(result, fragment)
        GLES20.glLinkProgram(result)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { "Link OpenGL: ${GLES20.glGetProgramInfoLog(result)}" }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return result
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] != 0) { "Shader OpenGL: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private fun makeCurrent() {
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
            "eglMakeCurrent failed 0x${EGL14.eglGetError().toString(16)}"
        }
    }

    private fun checkGl(operation: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "$operation GL error 0x${error.toString(16)}" }
    }

    override fun close() {
        if (closed) return
        closed = true
        surfaceTexture?.setOnFrameAvailableListener(null)
        surface.release()
        runCatching {
            makeCurrent()
            surfaceTexture?.release()
            surfaceTexture = null
            if (program != 0) GLES20.glDeleteProgram(program)
            if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            if (outputTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(outputTexture), 0)
            if (externalTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(externalTexture), 0)
        }
        EGL14.eglMakeCurrent(
            display,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT,
        )
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display)
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private companion object {
        const val OUTPUT_WIDTH = 320
        const val OUTPUT_HEIGHT = 180

        val VERTICES: FloatBuffer = floatBufferOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        )
        val TEX_COORDS: FloatBuffer = floatBufferOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
        )

        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        fun floatBufferOf(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }
    }
}

internal data class PrivateGlFrame(
    val rgba: ByteBuffer,
    val width: Int,
    val height: Int,
    val capturedElapsedRealtime: Long,
    val gpuReadbackNanos: Long,
)
