package com.vynox.app.render.gl

import android.opengl.GLES20
import android.util.Log

object GLError {
    private const val TAG = "VynoxGL"

    fun check(operation: String) {
        var error = GLES20.glGetError()
        while (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "$operation: glError 0x${Integer.toHexString(error)}")
            error = GLES20.glGetError()
        }
    }
}

/** Thin wrapper around a linked GLES 2.0 program with uniform caching. */
class GLProgram(vertexSource: String, fragmentSource: String) {

    val programId: Int

    private val uniforms = HashMap<String, Int>()
    private val attributes = HashMap<String, Int>()

    init {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val message = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw IllegalStateException("Program link failed: $message")
        }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        programId = program
    }

    fun use() {
        GLES20.glUseProgram(programId)
    }

    private fun uniform(name: String): Int {
        val cached = uniforms[name]
        if (cached != null) return cached
        val location = GLES20.glGetUniformLocation(programId, name)
        uniforms[name] = location
        return location
    }

    fun attribute(name: String): Int {
        val cached = attributes[name]
        if (cached != null) return cached
        val location = GLES20.glGetAttribLocation(programId, name)
        attributes[name] = location
        return location
    }

    fun setInt(name: String, value: Int) {
        val location = uniform(name)
        if (location >= 0) GLES20.glUniform1i(location, value)
    }

    fun setFloat(name: String, value: Float) {
        val location = uniform(name)
        if (location >= 0) GLES20.glUniform1f(location, value)
    }

    fun setVec2(name: String, x: Float, y: Float) {
        val location = uniform(name)
        if (location >= 0) GLES20.glUniform2f(location, x, y)
    }

    fun setVec3(name: String, x: Float, y: Float, z: Float) {
        val location = uniform(name)
        if (location >= 0) GLES20.glUniform3f(location, x, y, z)
    }

    fun setVec4(name: String, x: Float, y: Float, z: Float, w: Float) {
        val location = uniform(name)
        if (location >= 0) GLES20.glUniform4f(location, x, y, z, w)
    }

    fun setMatrix3(name: String, values: FloatArray) {
        val location = uniform(name)
        if (location >= 0) GLES20.glUniformMatrix3fv(location, 1, false, values, 0)
    }

    fun bindTexture(name: String, unit: Int, textureId: Int, target: Int = GLES20.GL_TEXTURE_2D) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(target, textureId)
        setInt(name, unit)
    }

    fun dispose() {
        GLES20.glDeleteProgram(programId)
    }

    companion object {
        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val message = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw IllegalStateException("Shader compile failed: $message\n$source")
            }
            return shader
        }
    }
}

/**
 * Unit quad used by every pass. Positions are 0..1 so the transform matrix does
 * all the work (content -> canvas -> clip space).
 */
object Quad {
    private val vertices = floatArrayOf(
        0f, 0f, 0f, 0f,
        1f, 0f, 1f, 0f,
        0f, 1f, 0f, 1f,
        1f, 1f, 1f, 1f
    )

    private val buffer = java.nio.ByteBuffer.allocateDirect(vertices.size * 4)
        .order(java.nio.ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(vertices)
            position(0)
        }

    private const val STRIDE = 16

    fun bind(program: GLProgram, positionName: String = "aPosition", texName: String = "aTexCoord") {
        val position = program.attribute(positionName)
        val texCoord = program.attribute(texName)
        buffer.position(0)
        if (position >= 0) {
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, STRIDE, buffer)
        }
        buffer.position(2)
        if (texCoord >= 0) {
            GLES20.glEnableVertexAttribArray(texCoord)
            GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, STRIDE, buffer)
        }
    }

    fun draw() {
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
}
