package com.keel.uds.runtime

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.SocketChannel

object PluginUdsFrameCodec {
    fun write(socketChannel: SocketChannel, payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        require(bytes.size <= PluginUdsLimits.MAX_FRAME_BYTES) {
            "UDS frame too large: ${bytes.size}"
        }
        val header = ByteBuffer.allocate(Int.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(bytes.size)
            .flip()
        while (header.hasRemaining()) {
            socketChannel.write(header)
        }
        val body = ByteBuffer.wrap(bytes)
        while (body.hasRemaining()) {
            socketChannel.write(body)
        }
    }

    fun read(socketChannel: SocketChannel): String {
        val input = Channels.newInputStream(socketChannel)
        return read(input)
    }

    fun read(input: InputStream): String {
        val sizeBuffer = ByteArray(Int.SIZE_BYTES)
        readFully(input, sizeBuffer)
        val size = ByteBuffer.wrap(sizeBuffer).order(ByteOrder.BIG_ENDIAN).int
        require(size in 0..PluginUdsLimits.MAX_FRAME_BYTES) { "Invalid UDS frame size: $size" }
        val body = ByteArray(size)
        readFully(input, body)
        return body.toString(Charsets.UTF_8)
    }

    fun write(output: OutputStream, payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        require(bytes.size <= PluginUdsLimits.MAX_FRAME_BYTES) {
            "UDS frame too large: ${bytes.size}"
        }
        val header = ByteBuffer.allocate(Int.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(bytes.size)
            .array()
        output.write(header)
        output.write(bytes)
        output.flush()
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) {
                throw EOFException("Unexpected EOF while reading UDS frame")
            }
            offset += read
        }
    }
}
