package dev.ide.android.spike

import androidx.activity.ComponentActivity

/**
 * A bare [ComponentActivity] the [dev.ide.android.spike.ComposeAbiSpikeTest] launches via `ActivityScenario`;
 * the test installs its own `setContent { … }`. ComponentActivity already provides the lifecycle/saved-state/
 * view-tree owners Compose needs, so no theme or layout is required.
 *
 * Lives in the **debug** source set (not androidTest): a test-only activity declared in the androidTest
 * manifest belongs to the test package (`…​.test`) and `ActivityScenario` — which launches against the app's
 * target process — rejects it as a cross-process intent. Declaring it in the app's debug variant keeps it in
 * the instrumentation target process while keeping it out of release builds.
 */
class SpikeComposeActivity : ComponentActivity()
