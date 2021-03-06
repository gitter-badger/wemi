package com.darkyen.wemi.intellij.util

/**
 *
 */

import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.ArchiveFileSystem
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Stream utilities
 */

fun readFully(into: OutputStream, stream: InputStream, buffer:ByteArray = ByteArray(1024)):Int {
    var read = 0
    while (true) {
        val available = stream.available()
        if (available <= 0) {
            break
        }
        val size = stream.read(buffer, 0, minOf(buffer.size, available))
        if (size == -1) {
            break
        }
        read += size
        into.write(buffer, 0, size)
    }
    return read
}

/**
 * OutputStream that buffers bytes until they can be converted to text using given charset and until the
 * text forms a valid line, ended with '\n'. Then it takes the line and calls [onLineRead] with it, without the line end.
 *
 * [close] to obtain the last line without ending '\n'.
 */
open class LineReadingOutputStream(charset: Charset = Charsets.UTF_8, private val onLineRead: (CharSequence) -> Unit) : OutputStream() {

    private val decoder: CharsetDecoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)

    private val inputBuffer: ByteBuffer = ByteBuffer.allocate(1024)
    private val outputBuffer: CharBuffer = CharBuffer.allocate(1024)
    private val outputSB = StringBuilder()

    init {
        inputBuffer.clear()
        outputBuffer.clear()
    }

    private fun flushLine() {
        onLineRead(outputSB)
        outputSB.setLength(0)
    }

    private fun decode(endOfInput: Boolean) {
        inputBuffer.flip()
        while (true) {
            val result = decoder.decode(inputBuffer, outputBuffer, endOfInput)
            outputBuffer.flip()

            outputSB.ensureCapacity(outputBuffer.limit())
            for (i in 0 until outputBuffer.limit()) {
                val c = outputBuffer[i]
                outputSB.append(c)

                if (c == '\n') {
                    // Flush outputSB!
                    flushLine()
                }
            }
            outputBuffer.position(0)
            outputBuffer.limit(outputBuffer.capacity())

            if (result.isUnderflow) {
                break
            }
        }

        inputBuffer.compact()
    }

    override fun write(b: Int) {
        inputBuffer.put(b.toByte())
        decode(false)
    }

    override fun write(b: ByteArray) {
        write(b, 0, b.size)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var offset = off
        var remaining = len

        while (remaining > 0) {
            val toConsume = minOf(remaining, inputBuffer.remaining())
            inputBuffer.put(b, offset, toConsume)
            offset += toConsume
            remaining -= toConsume
            decode(false)
        }
    }

    /**
     * Flushes the pending, unfinished line.
     * Writing further bytes into the stream after closing it leads to an undefined behavior.
     */
    override fun close() {
        decode(true)
        flushLine()
    }
}

fun VirtualFile?.toPath(): Path? {
    val localPath = this?.let { wrappedFile ->
        val wrapFileSystem = wrappedFile.fileSystem
        if (wrapFileSystem is ArchiveFileSystem) {
            wrapFileSystem.getLocalByEntry(wrappedFile)
        } else {
            wrappedFile
        }
    }

    if (localPath?.isInLocalFileSystem != true) {
        return null
    }

    // Based on LocalFileSystemBase.java
    var path = localPath.path
    if (StringUtil.endsWithChar(path, ':') && path.length == 2 && SystemInfo.isWindows) {
        path += "/"
    }

    return Paths.get(path)
}