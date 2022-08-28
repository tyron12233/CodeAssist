package com.tyron.builder.gradle.internal.dsl

import com.google.common.base.MoreObjects
import com.tyron.builder.gradle.internal.dsl.decorator.annotation.WithLazyInitialization
import com.tyron.builder.gradle.internal.services.DslServices
import com.tyron.builder.signing.DefaultSigningConfig
import org.gradle.api.Named
import java.io.File
import java.io.Serializable
import java.security.KeyStore
import javax.inject.Inject

abstract class SigningConfig @Inject @WithLazyInitialization("lazyInit") constructor(name: String, dslServices: DslServices) : DefaultSigningConfig(name),
    Serializable, Named, com.tyron.builder.api.dsl.ApkSigningConfig, InternalSigningConfig {

    fun lazyInit() {
        storeType = KeyStore.getDefaultType()
    }

    override fun initWith(that: com.tyron.builder.api.dsl.SigningConfig) {
        if (that !is SigningConfig) {
            throw RuntimeException("Unexpected implementation type")
        }
        initWith(that as DefaultSigningConfig)
    }

    fun initWith(that: SigningConfig): SigningConfig {
        return initWith(that as DefaultSigningConfig)
    }

    fun initWith(that: DefaultSigningConfig): SigningConfig {
        setStoreFile(that.storeFile)
        setStorePassword(that.storePassword)
        setKeyAlias(that.keyAlias)
        setKeyPassword(that.keyPassword)
        // setting isV1SigningEnabled and isV2SigningEnabled here might incorrectly set
        // enableV1Signing and/or enableV2Signing, but they'll be reset correctly below if so.
        isV1SigningEnabled = that.isV1SigningEnabled
        isV2SigningEnabled = that.isV2SigningEnabled
        enableV1Signing = that.enableV1Signing
        enableV2Signing = that.enableV2Signing
        enableV3Signing = that.enableV3Signing
        enableV4Signing = that.enableV4Signing
        setStoreType(that.storeType)
        return this
    }

    override fun toString(): String {
        return MoreObjects.toStringHelper(this)
            .add("name", name)
            .add("storeFile", storeFile?.absolutePath ?: "null")
            .add("storePassword", storePassword)
            .add("keyAlias", keyAlias)
            .add("keyPassword", keyPassword)
            .add("storeType", storeType)
            .add("v1SigningEnabled", isV1SigningEnabled)
            .add("v2SigningEnabled", isV2SigningEnabled)
            .add("enableV1Signing", enableV1Signing)
            .add("enableV2Signing", enableV2Signing)
            .add("enableV3Signing", enableV3Signing)
            .add("enableV4Signing", enableV4Signing)
            .toString()
    }

    // The following setters exist because of a bug where gradle is generating two groovy setters
    // for each field, since each value exists twice in the implemented interfaces
    // TODO - do we need setters for v3 and v4 here as well?

    open fun storeFile(storeFile: File?) {
        this.storeFile = storeFile
    }

    open fun storePassword(storePassword: String?) {
        this.storePassword = storePassword
    }

    open fun keyAlias(keyAlias: String?) {
        this.keyAlias = keyAlias
    }

    open fun keyPassword(keyPassword: String?) {
        this.keyPassword = keyPassword
    }

    open fun storeType(storeType: String?) {
        this.storeType = storeType
    }
}