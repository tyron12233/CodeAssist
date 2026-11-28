package dev.ide.vm

import dev.ide.platform.MemoryFileSystem
import dev.ide.platform.fileInfo
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// Node's own `fs`, reached through `process.getBuiltinModule` (an ES-module test has no `require`). The bytes
// cross as one base64 string: copying a multi-megabyte Uint8Array element by element would be millions of
// interop calls.
// Null for anything that is not a regular file: the class path also names output directories, some of which
// do not exist.
@JsFun("""(p) => {
    const fs = process.getBuiltinModule('fs');
    try { return fs.statSync(p).isFile() ? fs.readFileSync(p).toString('base64') : null; } catch (e) { return null; }
}""")
private external fun readHostFileBase64(path: String): String?

@OptIn(ExperimentalEncodingApi::class)
internal actual fun mountTestClasspath() {
    for (path in TestClasspath.entries) {
        if (fileInfo(path) != null) continue
        val encoded = readHostFileBase64(path) ?: continue
        MemoryFileSystem.write(path, Base64.decode(encoded))
    }
}
