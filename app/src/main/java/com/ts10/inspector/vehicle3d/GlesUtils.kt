package com.ts10.inspector.vehicle3d

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal fun rgbaBottomUpToArgb(source: IntArray, target: IntArray, width: Int, height: Int) {
    require(source.size >= width * height && target.size >= width * height)
    for (y in 0 until height) for (x in 0 until width) {
        val rgba = source[(height - 1 - y) * width + x]
        target[y * width + x] = (rgba and -0x1000000) or
            ((rgba and 0x000000ff) shl 16) or (rgba and 0x0000ff00) or
            ((rgba and 0x00ff0000) ushr 16)
    }
}

internal fun floatBuffer(values: FloatArray): FloatBuffer = ByteBuffer
    .allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    .apply { put(values); position(0) }

private fun compileShader(type: Int, source: String): Int = GLES20.glCreateShader(type).also { shader ->
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    val status = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
    check(status[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
}

internal fun linkProgram(vertex: String, fragment: String): Int {
    val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertex)
    val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment)
    return GLES20.glCreateProgram().also { program ->
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        check(status[0] != 0) { GLES20.glGetProgramInfoLog(program) }
    }
}
