package dev.codeassist.swift

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/**
 * What the user sees if they launch this app directly: what the plugin is, and where what it carries comes from. A
 * plugin app exists to be discovered by CodeAssist; saying so is better than a blank screen, which reads as a broken
 * install.
 */
class PluginInfoActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ScrollView(this).apply {
            addView(TextView(this@PluginInfoActivity).apply {
                setPadding(48, 48, 48, 48)
                setTextIsSelectable(true)
                textSize = 14f
                text = INFO
            })
        })
    }

    private companion object {
        val INFO = """
            CodeAssist Swift

            A plugin for CodeAssist, not an app of its own.

            It adds Swift: a Swift Console App template, and Run, which compiles a Swift package's program on this
            device and runs it in the Run console, with the standard library and Foundation.

            Open CodeAssist, then Settings and tools > Plugins to allow it to run.

            What it carries
            ===============
            Swift ${SwiftToolchain.VERSION}: the compiler, the Swift runtime, Foundation and swift-corelibs-libdispatch,
            by the Swift project (swift.org), Apache License 2.0 with the Runtime Library Exception. It includes LLVM
            and Clang (Apache License 2.0 with LLVM Exceptions) and ICU (Unicode License).

            Built for Android by the Termux project (github.com/termux/termux-packages), with Termux's builds of
            libc++ (LLVM), libuuid (util-linux, BSD), zstd (BSD), and of the Android NDK's C
            headers (Apache License 2.0).
        """.trimIndent()
    }
}
