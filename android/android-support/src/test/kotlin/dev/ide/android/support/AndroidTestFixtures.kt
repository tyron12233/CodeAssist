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
