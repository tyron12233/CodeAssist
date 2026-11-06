package com.zipcli.ke.fs

import java.io.InputStream
import java.io.OutputStream

class FsException(message: String) : Exception(message)

interface TerminalFileSystem {
    suspend fun list(path: String): List<FileEntry>
    suspend fun stat(path: String): FileEntry?
    suspend fun exists(path: String): Boolean

    suspend fun mkdirs(path: String)
    suspend fun createFile(path: String)

    suspend fun readText(path: String): String
    suspend fun writeText(path: String, content: String)

    suspend fun copy(src: String, dst: String, recursive: Boolean)
    suspend fun move(src: String, dst: String)
    suspend fun delete(path: String, recursive: Boolean)

    suspend fun walk(root: String): List<FileEntry>

    suspend fun openRead(path: String): InputStream
    suspend fun openWrite(path: String, append: Boolean = false): OutputStream
}