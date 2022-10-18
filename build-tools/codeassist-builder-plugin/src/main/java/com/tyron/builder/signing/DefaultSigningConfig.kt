package com.tyron.builder.signing

import com.tyron.builder.model.SigningConfig
import com.google.common.base.MoreObjects
import java.io.File
import java.security.KeyStore

/**
 * SigningConfig encapsulates the information necessary to access certificates in a keystore file
 * that can be used to sign APKs.
 */
abstract class DefaultSigningConfig constructor(private val mName: String) : SigningConfig {
    companion object {
        const val DEFAULT_PASSWORD = "android"
        const val DEFAULT_ALIAS = "AndroidDebugKey"

        /**
         * Creates a [DebugSigningConfig] that uses the default debug alias and passwords.
         */
        @JvmStatic
        fun debugSigningConfig(storeFile: File): DebugSigningConfig {
            return DebugSigningConfig(storeFile)
        }
    }
    protected abstract var _storeFilePath: String?
    override var storeFile: File?
        get() = _storeFilePath?.let { File(it) }
        set(value) { _storeFilePath = value?.path }
    abstract override var storePassword: String?
    abstract override var keyAlias: String?
    abstract override var keyPassword: String?
    abstract override var storeType: String?

    override var isV1SigningEnabled = true
        set(value) {
            enableV1Signing = value
            field = value
        }
    override var isV2SigningEnabled = true
        set(value) {
            enableV2Signing = value
            field = value
        }

    abstract var enableV1Signing: Boolean?
    abstract var enableV2Signing: Boolean?
    abstract var enableV3Signing: Boolean?
    abstract var enableV4Signing: Boolean?

    override val isSigningReady: Boolean
        get() = storeFile != null &&
                storePassword != null &&
                keyAlias != null &&
                keyPassword != null

    override fun getName() = mName

    fun setStoreFile(storeFile: File?): DefaultSigningConfig {
        this.storeFile = storeFile
        return this
    }

    fun setStorePassword(storePassword: String?): DefaultSigningConfig {
        this.storePassword = storePassword
        return this
    }

    fun setKeyAlias(keyAlias: String?): DefaultSigningConfig {
        this.keyAlias = keyAlias
        return this
    }

    fun setKeyPassword(keyPassword: String?): DefaultSigningConfig {
        this.keyPassword = keyPassword
        return this
    }

    fun setStoreType(storeType: String?): DefaultSigningConfig {
        this.storeType = storeType
        return this
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class.java != other::class.java) return false

        val that = other as DefaultSigningConfig

        if (keyAlias != that.keyAlias) {
            return false
        }

        if (keyPassword != that.keyPassword) {
            return false
        }

        if (storeFile != that.storeFile) {
            return false
        }

        if (storePassword != that.storePassword) {
            return false
        }

        if (storeType != that.storeType) {
            return false
        }
        @Suppress("DEPRECATION") // Legacy support
        run {
            if (isV1SigningEnabled != that.isV1SigningEnabled) return false
            if (isV2SigningEnabled != that.isV2SigningEnabled) return false
        }
        if (enableV1Signing != that.enableV1Signing) return false
        if (enableV2Signing != that.enableV2Signing) return false
        if (enableV3Signing != that.enableV3Signing) return false
        if (enableV4Signing != that.enableV4Signing) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + (storeFile?.hashCode() ?: 0)
        result = 31 * result + (storePassword?.hashCode() ?: 0)
        result = 31 * result + (keyAlias?.hashCode() ?: 0)
        result = 31 * result + (keyPassword?.hashCode() ?: 0)
        result = 31 * result + (storeType?.hashCode() ?: 0)
        @Suppress("DEPRECATION") // Legacy support
        run {
            result = 31 * result + (if (isV1SigningEnabled) 17 else 0)
            result = 31 * result + (if (isV2SigningEnabled) 17 else 0)
        }
        result = 31 * result + (enableV1Signing?.hashCode() ?: 0)
        result = 31 * result + (enableV2Signing?.hashCode() ?: 0)
        result = 31 * result + (enableV3Signing?.hashCode() ?: 0)
        result = 31 * result + (enableV4Signing?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        @Suppress("DEPRECATION") // Legacy support
        return MoreObjects.toStringHelper(this)
            .add("storeFile", storeFile?.absolutePath)
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

    class DebugSigningConfig(val storeFile: File) {
        val storePassword: String get() = DEFAULT_PASSWORD
        val keyAlias: String get() = DEFAULT_ALIAS
        val keyPassword: String get() = DEFAULT_PASSWORD
        val storeType: String get() = KeyStore.getDefaultType()
        fun copyToSigningConfig(other: DefaultSigningConfig) {
            other.storeFile = storeFile
            other.storePassword = storePassword
            other.keyAlias = keyAlias
            other.keyPassword = keyPassword
            other.storeType = storeType
        }
    }
}