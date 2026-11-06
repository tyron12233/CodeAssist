package dev.codeassist.dart

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * The one way into the Flutter player from outside this app, and the check on who is asking.
 *
 * The player hands whoever starts it the address of its Dart VM service, auth token included, and the VM
 * service runs any code it is given, as this app. So the player is not exported; this Activity is, and starts
 * it only for CodeAssist itself ([TRUSTED_HOSTS]), refusing everyone else without starting an engine.
 *
 * Who is asking: on Android 14 and later the system says, when the caller shares its identity (the IDE does,
 * with `ActivityOptions.setShareIdentityEnabled`). Before that the referrer is the caller's package, unless the
 * intent carries a referrer extra of its own, which only a caller pretending to be someone else would add.
 */
class FlutterPlayerLauncher : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val caller = callerPackage()
        if (caller != null && trusted(caller)) {
            startActivity(
                Intent(this, FlutterPlayerActivity::class.java)
                    .putExtras(intent)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } else {
            Log.w(TAG, "refused to start the Flutter player for ${caller ?: "an unidentified caller"}")
        }
        finish()
    }

    private fun callerPackage(): String? {
        if (Build.VERSION.SDK_INT >= 34) launchedFromPackage?.let { return it }
        if (intent.hasExtra(Intent.EXTRA_REFERRER) || intent.hasExtra(Intent.EXTRA_REFERRER_NAME)) return null
        return referrer?.takeIf { it.scheme == "android-app" }?.host
    }

    private fun trusted(caller: String): Boolean {
        if (caller in TRUSTED_HOSTS) return true
        // A debuggable build of the plugin also answers adb, which is already a more privileged caller.
        return caller == "com.android.shell" && (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    companion object {
        private const val TAG = "FlutterPlayerLauncher"

        /** The CodeAssist apps the player runs Flutter apps for. */
        val TRUSTED_HOSTS = setOf("com.tyron.code")
    }
}
