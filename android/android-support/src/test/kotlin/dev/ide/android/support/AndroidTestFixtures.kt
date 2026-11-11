package dev.ide.android.support

import dev.ide.android.support.tools.AndroidSdk
import org.junit.jupiter.api.Assumptions.assumeTrue

/** The installed Android SDK (platform + build-tools), or null when none is available/complete. */
fun detectAndroidSdk(): AndroidSdk? = AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }

/**
 * The installed Android SDK, or skip the test (a JUnit assumption, not a failure) when none is installed.
 * Replaces the ~25 verbatim `assumeTrue(sdk != null && sdk.isComplete(), ...)` guards across the suite.
 */
fun assumeAndroidSdk(): AndroidSdk {
    val sdk = detectAndroidSdk()
    assumeTrue(sdk != null && sdk.isComplete(), "Android SDK (platform + build-tools) not installed; skipping")
    return sdk!!
}

/** Which signature schemes [apk] verifies under, as a device would check it: (v1, v2). Fails on an APK that does not verify. */
fun verifiedSignatureSchemes(apk: java.nio.file.Path, minSdk: Int): Pair<Boolean, Boolean> {
    val result = com.android.apksig.ApkVerifier.Builder(apk.toFile()).setMinCheckedPlatformVersion(minSdk).build().verify()
    org.junit.jupiter.api.Assertions.assertTrue(result.isVerified, "the APK does not verify on API $minSdk and up: ${result.errors}")
    return result.isVerifiedUsingV1Scheme to result.isVerifiedUsingV2Scheme
}
