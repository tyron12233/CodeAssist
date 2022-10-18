package com.tyron.builder.common.resources

import java.io.Closeable
import java.io.File
import java.io.IOException

/** Abstraction for resource compiler services used by the resource merger. */
interface ResourceCompilationService : Closeable {
    /** Submit a request. */
    @Throws(IOException::class)
    fun submitCompile(request: CompileResourceRequest)
    /** Given a request, returns the output file that would, will or has been written. */
    fun compileOutputFor(request: CompileResourceRequest): File
}