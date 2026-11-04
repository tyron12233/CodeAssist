package dev.codeassist.dart

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * What the user sees if they launch this app directly. A plugin app exists to be discovered by CodeAssist;
 * saying so is better than a blank screen, which reads as a broken install.
 */
class PluginInfoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                setPadding(48, 48, 48, 48)
                text = buildString {
                    appendLine("CodeAssist Dart")
                    appendLine()
                    appendLine("A plugin for CodeAssist, not an app of its own.")
                    appendLine()
                    appendLine("It adds Dart: errors, completion and quick fixes from Dart's analysis server,")
                    appendLine("Run for command-line programs, and pub get, with a Dart SDK for arm64 inside.")
                    appendLine()
                    appendLine("Open CodeAssist, then Settings and tools > Plugins to allow it to run.")
                }
            }
        )
    }
}
