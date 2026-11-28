package dev.ide.playground

import dev.ide.platform.MemoryFileSystem
import dev.ide.platform.fileInfo
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// Node's `fs`, reached through `process.getBuiltinModule` (an ES-module test has no `require`). The bytes cross
// as one base64 string: copying a Uint8Array element by element would be millions of interop calls.
// Null for anything that is not a regular file.
@JsFun("""(p) => {
    const fs = process.getBuiltinModule('fs');
    try { return fs.statSync(p).isFile() ? fs.readFileSync(p).toString('base64') : null; } catch (e) { return null; }
}""")
private external fun readHostFileBase64(path: String): String?

/**
 * Copy [paths] from the host disk into the in-memory file system. In the browser this step is a `fetch`; under
 * Node it is the disk, which is the only difference the rest of the pipeline can see.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun mountHostFiles(paths: List<String>) {
    for (path in paths) {
        if (fileInfo(path) != null) continue
        val encoded = readHostFileBase64(path) ?: error("cannot read $path on the host")
        MemoryFileSystem.write(path, Base64.decode(encoded))
    }
}
