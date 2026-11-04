package dev.codeassist.dart

import android.os.Bundle
import android.os.ResultReceiver
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterJNI

/**
 * The Flutter player. It starts on a few-kilobyte `dart:ui` program ("Waiting for CodeAssist...") and is then
 * handed the real app through its Dart VM service, the way `flutter run` restarts and hot-reloads an app.
 *
 * Runs in this plugin's own process, started only by [FlutterPlayerLauncher] (it is not exported), which
 * passes on the IDE's [ResultReceiver] in [EXTRA_RECEIVER]; the player answers it with its VM service URI
 * (auth token included) and its isolate once the engine has them.
 */
class FlutterPlayerActivity : FlutterActivity() {

    /**
     * No plugins: an app's Flutter plugins (packages with Android code of their own) are not in the player, so
     * there is no GeneratedPluginRegistrant to call, and the default implementation logs its absence as an error.
     */
    override fun configureFlutterEngine(flutterEngine: io.flutter.embedding.engine.FlutterEngine) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reportVmService()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        reportVmService()
    }

    /** Send the VM service URI to whoever started this run, as soon as the engine publishes it. */
    private fun reportVmService() {
        val receiver = intent.getParcelableExtra<ResultReceiver>(EXTRA_RECEIVER) ?: return
        intent.removeExtra(EXTRA_RECEIVER)
        Thread({
            var uri: String? = null
            var isolate: String? = null
            val deadline = System.currentTimeMillis() + 20_000
            while ((uri == null || isolate == null) && System.currentTimeMillis() < deadline) {
                uri = uri ?: runCatching { FlutterJNI.getVMServiceUri() }.getOrNull()?.takeIf { it.isNotBlank() }
                isolate = isolate ?: runCatching { flutterEngine?.dartExecutor?.isolateServiceId }.getOrNull()
                if (uri == null || isolate == null) Thread.sleep(50)
            }
            receiver.send(if (uri != null) 0 else 1, Bundle().apply {
                putString(EXTRA_URI, uri)
                putString(EXTRA_ISOLATE, isolate)
            })
        }, "flutter-player-vm-service").apply { isDaemon = true }.start()
    }

    companion object {
        const val EXTRA_RECEIVER = "dev.codeassist.dart.VM_SERVICE_RECEIVER"
        const val EXTRA_URI = "uri"
        /** The isolate the player's engine started, which picks its view out of the VM's. */
        const val EXTRA_ISOLATE = "isolate"
    }
}
