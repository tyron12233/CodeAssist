package com.tyron.builder.internal.aapt.v2

import com.android.ide.common.resources.CompileResourceRequest
import com.android.utils.ILogger
import com.tyron.builder.internal.aapt.AaptConvertConfig
import com.tyron.builder.internal.aapt.AaptPackageConfig

/**
 * The operations AAPT2 can perform.
 *
 * Methods throw [Aapt2Exception] for invalid input (e.g. syntax error in a source file)
 * and [Aapt2InternalException] if there is an internal issue running AAPT itself.
 */
interface Aapt2 {
    /** Perform the requested compilation. Throws [Aapt2Exception] on failure */
    fun compile(request: CompileResourceRequest, logger: ILogger)

    /** Perform the requested linking. Throws [Aapt2Exception] on failure. */
    fun link(request: AaptPackageConfig, logger: ILogger)

    /**
     * Perform the requested conversion between proto/binary resources. Throws [Aapt2Exception] on
     * failure.
     */
    fun convert(request: AaptConvertConfig, logger: ILogger)
}