package dev.codeassist.lspdemo

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** What the user sees if they launch this app directly: it is a plugin for CodeAssist, not an app. */
class PluginInfoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                setPadding(48, 48, 48, 48)
                text = buildString {
                    appendLine("CodeAssist Calc")
                    appendLine()
                    appendLine("A sample plugin for CodeAssist, not an app of its own.")
                    appendLine()
                    appendLine("It teaches the editor a tiny language, Calc (.calc files), through a language")
                    appendLine("server: errors, completion, hover, rename, quick fixes and more.")
                    appendLine()
                    appendLine("Open CodeAssist, then Settings and tools > Plugins to allow it to run.")
                }
            }
        )
    }
}
