package dev.ide.android

import android.app.Application
import android.content.Context
import android.os.Build
import rikka.shizuku.ShizukuProvider
import java.io.File

class CodeAssistApplication : Application() {
    override fun attachBaseContext(base: Context) {
        // ActivityThread installs providers after Application.attachBaseContext but before onCreate.
        ShizukuProvider.enableMultiProcessSupport(processName(base.packageName) == base.packageName)
        super.attachBaseContext(base)
    }

    private fun processName(fallback: String): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        getProcessName()
    } else {
        runCatching { File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrDefault(fallback)
    }
}
