package dev.codeassist.dart

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import android.util.Log

/**
 * The one way into the Flutter player from outside this app, and the check on who is asking.
 *
 * The player hands whoever starts it the address of its Dart VM service, auth token included, and the VM
 * service runs any code it is given, as this app. So the player is not exported; this Activity is, and starts
 * it only for CodeAssist itself ([TRUSTED_HOSTS]), refusing everyone else without starting an engine.
 *
 * Who is asking: on Android 14 and later the system says, when the caller shares its identity (the IDE does,
 * with `ActivityOptions.setShareIdentityEnabled`). Before that, the creator of the PendingIntent the caller passes
 * in [FlutterPlayerActivity.EXTRA_IDENTITY], which the system records and no app can forge. The referrer is the
 * last resort: Android fills it in only for a start from an Activity, which the IDE's build process has none of,
 * and a referrer extra the intent carries itself is what a caller pretending to be someone else would add.
 */
class FlutterPlayerLauncher : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val caller = callerPackage()
        // Answered at once either way, so the IDE can tell a launch the system blocked (nothing answers) from
        // one refused here or a player that started and then failed.
        val receiver = runCatching { intent.getParcelableExtra<ResultReceiver>(FlutterPlayerActivity.EXTRA_RECEIVER) }.getOrNull()
        if (caller != null && trusted(caller)) {
            startActivity(
                Intent(this, FlutterPlayerActivity::class.java)
                    .putExtras(intent)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            receiver?.send(FlutterPlayerActivity.RESULT_REACHED, null)
        } else {
            val who = caller ?: "an unidentified caller"
            Log.w(TAG, "refused to start the Flutter player for $who")
            receiver?.send(FlutterPlayerActivity.RESULT_REFUSED, Bundle().apply { putString(FlutterPlayerActivity.EXTRA_CALLER, who) })
        }
        finish()
    }

    private fun callerPackage(): String? {
        if (Build.VERSION.SDK_INT >= 34) launchedFromPackage?.let { return it }
        runCatching { intent.getParcelableExtra<PendingIntent>(FlutterPlayerActivity.EXTRA_IDENTITY)?.creatorPackage }
            .getOrNull()?.let { return it }
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
