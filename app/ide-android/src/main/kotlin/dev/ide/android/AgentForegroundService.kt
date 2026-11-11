package dev.ide.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.ide.platform.log.Log
import dev.ide.ui.backend.AgentService
import dev.ide.ui.backend.UiAgentChatState
import dev.ide.ui.backend.UiAgentRole
import dev.ide.ui.backend.UiAgentToolStatus
import dev.ide.ui.backend.UiMcpRelay
import dev.ide.ui.backend.UiMcpRelayStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the AI agent alive, and visible, while it works without the app on screen.
 *
 * Two things outlast the screen. An agent turn can run for minutes, calling the model and editing files, and the
 * Claude link ([AgentService.mcpRelay]) holds a connection open for as long as it is on. Without a foreground
 * service Android freezes or kills the process soon after the user switches away: the turn stops mid-edit and the
 * link drops, with nothing to say so. With one, the work carries on and an ongoing notification says what the
 * agent is doing, with a button to stop it.
 *
 * The service holds no state of its own. [AgentForeground] watches the agent and starts, updates and stops it.
 */
class AgentForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_TURN -> AgentForeground.agent?.stop()
            ACTION_STOP_LINK -> AgentForeground.agent?.setMcpRelayEnabled(false)
        }
        val content = AgentForeground.content
        if (content == null) {
            // Nothing to show: the work ended between the request to start and this call. A service started for
            // the foreground must still enter it once, so do, and leave.
            enter(Content("CodeAssist AI", "Finished", stopAction = null))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        enter(content)
        return START_NOT_STICKY
    }

    private fun enter(content: Content) {
        runCatching {
            val notification = notification(this, content)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            // Not allowed from the background on Android 12+. The agent still runs while the app is in front.
            log.warn("agent foreground service did not start: ${it.message}")
        }
    }

    /** What the notification says. [stopAction] is the intent action of its button, or null for none. */
    data class Content(val title: String, val text: String, val stopAction: String?, val stopLabel: String = "Stop")

    companion object {
        private val log = Log.logger("ide.agent")
        private const val CHANNEL_ID = "agent"
        const val NOTIFICATION_ID = 7302
        const val ACTION_STOP_TURN = "dev.ide.android.agent.STOP_TURN"
        const val ACTION_STOP_LINK = "dev.ide.android.agent.STOP_LINK"

        fun notification(context: Context, content: Content): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "AI agent", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Shown while the CodeAssist AI agent is working or the Claude link is on"
                    },
                )
            }
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
            }
            return Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(content.title)
                .setContentText(content.text)
                .setStyle(Notification.BigTextStyle().bigText(content.text))
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setOnlyAlertOnce(true) // every tool step re-posts; only the first may make a sound
                .apply {
                    open?.let { setContentIntent(it) }
                    content.stopAction?.let { action ->
                        val stop = PendingIntent.getService(
                            context, action.hashCode(),
                            Intent(context, AgentForegroundService::class.java).setAction(action),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        )
                        addAction(Notification.Action.Builder(null, content.stopLabel, stop).build())
                    }
                }
                .build()
        }
    }
}

/** Watches the agent and keeps [AgentForegroundService] in step with it. One per process. */
object AgentForeground {
    private val log = Log.logger("ide.agent")

    @Volatile var agent: AgentService? = null
        private set

    /** What the notification should say now, or null when there is nothing to keep alive. */
    @Volatile var content: AgentForegroundService.Content? = null
        private set

    private var running = false

    fun attach(context: Context, agent: AgentService, scope: CoroutineScope) {
        this.agent = agent
        val app = context.applicationContext
        scope.launch(Dispatchers.Main) {
            combine(agent.chatState, agent.mcpRelay) { chat, relay -> contentFor(chat, relay) }
                .distinctUntilChanged()
                .collect { next -> apply(app, next) }
        }
    }

    private fun apply(context: Context, next: AgentForegroundService.Content?) {
        content = next
        val intent = Intent(context, AgentForegroundService::class.java)
        when {
            next == null -> if (running) {
                running = false
                runCatching { context.stopService(intent) }
            }
            !running -> {
                // Allowed only while the app is in front, which is when a turn starts or the link is switched on.
                runCatching { context.startForegroundService(intent) }
                    .onSuccess { running = true }
                    .onFailure { log.warn("agent foreground service could not be started: ${it.message}") }
            }
            else -> runCatching {
                context.getSystemService(NotificationManager::class.java)
                    ?.notify(AgentForegroundService.NOTIFICATION_ID, AgentForegroundService.notification(context, next))
            }
        }
    }

    /**
     * A turn in progress wins the notification: it says the step the agent is on, and its button stops the turn.
     * With only the link on, it says whether Claude can reach the project, and its button turns the link off.
     */
    internal fun contentFor(chat: UiAgentChatState, relay: UiMcpRelay): AgentForegroundService.Content? {
        if (chat.busy) {
            val last = chat.messages.lastOrNull { it.role == UiAgentRole.ASSISTANT }
            val step = last?.toolCalls?.lastOrNull { it.status == UiAgentToolStatus.RUNNING }?.title
                ?: last?.toolCalls?.lastOrNull()?.title
            val waiting = last?.waitReason?.takeIf { last.waitUntilMs != null && it.isNotBlank() }
            return AgentForegroundService.Content(
                title = "CodeAssist AI is working",
                text = waiting ?: step ?: "Thinking…",
                stopAction = AgentForegroundService.ACTION_STOP_TURN,
            )
        }
        if (relay.enabled) {
            val text = when (relay.status) {
                UiMcpRelayStatus.CONNECTED ->
                    if (relay.inFlight > 0) "Claude is working on the open project." else "Claude can reach the open project."
                UiMcpRelayStatus.OFFLINE -> "No connection. Retrying…"
                UiMcpRelayStatus.SIGNED_OUT -> "Sign in to CodeAssist again to reconnect."
                UiMcpRelayStatus.IN_USE -> "This link belongs to another account."
                UiMcpRelayStatus.REPLACED -> "The link was opened in another CodeAssist."
                UiMcpRelayStatus.CONNECTING, UiMcpRelayStatus.OFF -> "Connecting…"
            }
            return AgentForegroundService.Content(
                title = "Claude link is on", text = text,
                stopAction = AgentForegroundService.ACTION_STOP_LINK, stopLabel = "Turn off",
            )
        }
        return null
    }
}
