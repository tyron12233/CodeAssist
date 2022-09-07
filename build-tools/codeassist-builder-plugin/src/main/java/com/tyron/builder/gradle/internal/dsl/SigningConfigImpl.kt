package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.SigningConfig
import com.tyron.builder.gradle.internal.dsl.decorator.annotation.WithLazyInitialization
import com.tyron.builder.gradle.internal.services.DslServices
import java.io.File
import java.security.KeyStore
import javax.inject.Inject

abstract class SigningConfigImpl
@Inject
@WithLazyInitialization(methodName = "lazyInit")
constructor(dslServices: DslServices): SigningConfig {

    protected abstract var _storeFile: String?

    @Suppress("unused") // used by @WithLazyInitialization
    protected fun lazyInit() {
        storeType = KeyStore.getDefaultType()
    }

    override var storeFile: File?
        get() = _storeFile?.let { File(it) }
        set(value) { _storeFile = value?.absolutePath }

}
