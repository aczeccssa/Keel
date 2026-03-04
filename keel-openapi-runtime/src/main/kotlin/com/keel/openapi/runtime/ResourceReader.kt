package com.keel.openapi.runtime

import java.io.FileNotFoundException
import java.nio.charset.Charset

object ResourceReader {
    fun read(filename: String): ByteArray {
        val target = if (filename.startsWith("/")) filename else "/$filename"
        return this::class.java.getResourceAsStream(target)?.readBytes()
            ?: throw FileNotFoundException("File not found: $filename")
    }

    fun readAsString(filename: String, charset: Charset = Charset.defaultCharset()) = String(read(filename), charset)
}
