package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.dsl.SigningConfig
import com.tyron.builder.api.variant.CodeTransparency

class CodeTransparencyImpl(
    dslSigningConfig: SigningConfig?
): CodeTransparency {

    internal var signingConfiguration: SigningConfig? = dslSigningConfig

    override fun setSigningConfig(signingConfig: SigningConfig) {
        this.signingConfiguration = signingConfig
    }
}