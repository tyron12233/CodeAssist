package com.tyron.builder.api.dsl

import org.gradle.api.Named

/** DSL object to configure signing configs. */
interface ApkSigningConfig: SigningConfig, Named {
    /**
     * Whether signing using JAR Signature Scheme (aka v1 signing) is enabled.
     *
     * See [Signing Your Applications](http://developer.android.com/tools/publishing/app-signing.html)
     */
    @Deprecated("This property is deprecated", ReplaceWith("enableV1Signing"))
    var isV1SigningEnabled: Boolean

    /**
     * Whether signing using APK Signature Scheme v2 (aka v2 signing) is enabled.
     *
     * See [Signing Your Applications](http://developer.android.com/tools/publishing/app-signing.html)
     */
    @Deprecated("This property is deprecated", ReplaceWith("enableV2Signing"))
    var isV2SigningEnabled: Boolean

    /**
     * Enable signing using JAR Signature Scheme (aka v1 signing). If null, a default value is used.
     *
     * See [Signing Your Applications](http://developer.android.com/tools/publishing/app-signing.html)
     */
    var enableV1Signing: Boolean?

    /**
     * Enable signing using APK Signature Scheme v2 (aka v2 signing). If null, a default value is
     * used.
     *
     * See [Signing Your Applications](http://developer.android.com/tools/publishing/app-signing.html)
     */
    var enableV2Signing: Boolean?

    /**
     * Enable signing using APK Signature Scheme v3 (aka v3 signing). If null, a default value is
     * used.
     *
     * See [APK Signature Scheme v3](https://source.android.com/security/apksigning/v3)
     */
    var enableV3Signing: Boolean?

    /**
     * Enable signing using APK Signature Scheme v4 (aka v4 signing). If null, a default value is
     * used.
     */
    var enableV4Signing: Boolean?
}