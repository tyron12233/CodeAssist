package dev.codeassist.ndk

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * What the user sees if they launch this app directly from the launcher.
 *
 * A plugin app is not meant to be opened; it exists to be discovered by CodeAssist. Saying so is better than
 * a blank screen, which reads as a broken install.
 */
class PluginInfoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                setPadding(48, 48, 48, 48)
                text = buildString {
                    appendLine("CodeAssist NDK")
                    appendLine()
                    appendLine("A plugin for CodeAssist, not an app of its own.")
                    appendLine()
                    appendLine("It adds C and C++ support: syntax colouring, errors from the compiler, and")
                    appendLine("an on-device clang and lld that build native code for arm64.")
                    appendLine()
                    appendLine("Open CodeAssist, then Settings and tools > Plugins to allow it to run.")
                }
            }
        )
    }
}
