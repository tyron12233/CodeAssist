package dev.ide.android

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.ide.analytics.AnalyticsEvent
import dev.ide.analytics.AnalyticsService
import dev.ide.analytics.EventCategory
import dev.ide.analytics.Events
import dev.ide.core.ReturnNudgeHost
import dev.ide.core.reengage.FailedBuild
import dev.ide.core.reengage.ProjectRef
import dev.ide.core.reengage.ReturnNudge
import dev.ide.core.reengage.ReturnNudgeKind
import dev.ide.core.reengage.ReturnNudges
import dev.ide.core.reengage.ReturnState
import dev.ide.platform.log.Log
import dev.ide.ui.backend.UiNotification
import dev.ide.ui.backend.UiNotificationKind
import dev.ide.ui.backend.UiNotificationTarget
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.nudge_failed_body
import dev.ide.ui.generated.resources.nudge_failed_title
import dev.ide.ui.generated.resources.nudge_first_body
import dev.ide.ui.generated.resources.nudge_first_title
import dev.ide.ui.generated.resources.nudge_never_body
import dev.ide.ui.generated.resources.nudge_never_title
import dev.ide.ui.generated.resources.nudge_unbuilt_body
import dev.ide.ui.generated.resources.nudge_unbuilt_title
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Reminders for someone who stopped using the app: when one is due is [ReturnNudges]'s rule, and this is the
 * Android half that remembers what the rule needs, wakes up when a reminder could be due, and shows it.
 *
 * Everything is on the device. The server knows nothing about whether an install has built anything unless
 * its user shares analytics, and a reminder about a build someone left broken should not depend on that.
 *
 * The check runs when the app is not running, so it reads its own small preferences file and never starts
 * the IDE's engine. The app writes to that file as it is used: when it comes to the front and leaves it
 * ([used]), and when a build ends ([buildFinished]).
 *
 * One install in [HOLDOUT_ONE_IN] is shown nothing. For it a reminder that came due is recorded and counted
 * exactly as for the others, which is what lets the two groups' return be compared.
 */
internal object ReturnReminders : ReturnNudgeHost {

    /** The app preference behind the switch in Settings (Privacy & Data); absent means on. */
    const val ENABLED_PREF = "settings.privacy.returnReminders"

    /**
     * An app preference that puts this install in the holdout (`true`) or out of it (`false`) whatever was
     * drawn. Nothing in the app writes it: it is for a test, which cannot depend on a one-in-ten draw.
     */
    const val HOLDOUT_PREF = "reminders.holdout"

    private const val FILE = "return_reminders"
    private const val WORK = "return-reminder"
    private const val TAG = "return-reminder"
    private const val HOLDOUT_ONE_IN = 10

    /** A check is never scheduled sooner than this, so a reminder cannot land minutes after the app was left. */
    private const val MIN_DELAY_MS = 15 * 60_000L

    private const val K_INSTALLED = "installedAt"
    private const val K_USED = "lastUsedAt"
    private const val K_BUILT = "everBuilt"
    private const val K_FAILED_ROOT = "failedRoot"
    private const val K_FAILED_NAME = "failedName"
    private const val K_FAILED_AT = "failedAt"
    private const val K_OPENED_ROOT = "openedRoot"
    private const val K_OPENED_NAME = "openedName"
    private const val K_FIRST_BUILT_AT = "firstBuiltAt"
    private const val K_BUILT_ROOT = "builtRoot"
    private const val K_BUILT_NAME = "builtName"
    private const val K_SENT = "sentCount"
    private const val K_SENT_AT = "lastSentAt"
    private const val K_SENT_KIND = "lastSentKind"
    private const val K_SENT_OUTCOME = "lastSentOutcome"
    private const val K_SENT_KEYS = "sentKeys"
    private const val K_ENABLED = "enabled"
    private const val K_HOLDOUT = "holdout"
    private const val K_EVENTS = "events"

    private val log = Log.logger("ide.reminders")

    /** One thread, so a use and a build recorded close together are written in the order they happened. */
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "return-reminders").apply { isDaemon = true }
    }

    @Volatile private var appContext: Context? = null

    /** Called once at startup, so [buildFinished] (which the engine calls with no context) can reach storage. */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * The app came to the front ([entering]) or left it. Either way the user was here just now, which moves
     * every reminder back, and the switch is read while there is an engine to read it from.
     */
    fun used(context: Context, entering: Boolean, enabled: Boolean?, holdout: Boolean? = null) {
        val app = context.applicationContext
        // Off the main thread: this reads a preferences file and talks to WorkManager, from onStart and onStop.
        io.execute { recordUse(app, entering, enabled, holdout) }
    }

    private fun recordUse(context: Context, entering: Boolean, enabled: Boolean?, holdout: Boolean?) {
        runCatching {
            val prefs = prefs(context)
            val now = System.currentTimeMillis()
            val edit = prefs.edit()
            if (!prefs.contains(K_INSTALLED)) {
                val installed = installTime(context, now)
                edit.putLong(K_INSTALLED, installed)
                // An install from before this existed has a history nothing recorded. Take it to have built
                // before, so someone who used the app for months is never told to build their first app.
                if (now - installed > ReturnNudges.DAY_MS) edit.putBoolean(K_BUILT, true)
            }
            if (holdout != null) edit.putBoolean(K_HOLDOUT, holdout)
            else if (!prefs.contains(K_HOLDOUT)) edit.putBoolean(K_HOLDOUT, Random.nextInt(HOLDOUT_ONE_IN) == 0)
            if (entering) {
                // Back after a reminder: the one number the reminders are judged by.
                val sentAt = prefs.getLong(K_SENT_AT, 0)
                if (sentAt > prefs.getLong(K_USED, 0)) {
                    queueEvent(
                        prefs, edit, "returned", prefs.getString(K_SENT_KIND, null).orEmpty(),
                        prefs.getString(K_SENT_OUTCOME, null).orEmpty(), now - sentAt,
                    )
                }
                context.getSystemService(NotificationManager::class.java)?.cancel(TAG, AndroidNotificationPresenter.TRAY_ID)
            }
            edit.putLong(K_USED, now)
            enabled?.let { edit.putBoolean(K_ENABLED, it) }
            edit.apply()
            schedule(context, read(prefs, lastUsedAt = now, enabled = enabled))
        }.onFailure { log.warn("return reminders: recording use failed", it) }
    }

    override fun buildFinished(projectRoot: String, projectName: String, succeeded: Boolean) {
        val context = appContext ?: return
        io.execute { recordBuild(context, projectRoot, projectName, succeeded) }
    }

    private fun recordBuild(context: Context, projectRoot: String, projectName: String, succeeded: Boolean) {
        runCatching {
            val prefs = prefs(context)
            val edit = prefs.edit()
            if (succeeded) {
                // The first build of an install that had not built: the one a follow-up is about.
                if (!prefs.getBoolean(K_BUILT, false)) edit.putLong(K_FIRST_BUILT_AT, System.currentTimeMillis())
                edit.putBoolean(K_BUILT, true).remove(K_FAILED_ROOT).remove(K_FAILED_NAME).remove(K_FAILED_AT)
                    .putString(K_BUILT_ROOT, projectRoot).putString(K_BUILT_NAME, projectName)
            } else {
                edit.putString(K_FAILED_ROOT, projectRoot).putString(K_FAILED_NAME, projectName)
                    .putLong(K_FAILED_AT, System.currentTimeMillis())
            }
            edit.apply()
            // No scheduling here: the app is in use, and leaving it ([used]) schedules from what is stored now.
        }.onFailure { log.warn("return reminders: recording a build failed", it) }
    }

    override fun projectOpened(projectRoot: String, projectName: String) {
        val context = appContext ?: return
        io.execute {
            runCatching {
                prefs(context).edit().putString(K_OPENED_ROOT, projectRoot).putString(K_OPENED_NAME, projectName).apply()
            }
        }
    }

    /** Hand what the checks recorded while the app was away to analytics (a no-op without consent). */
    fun flushEvents(context: Context, analytics: AnalyticsService) {
        runCatching {
            val prefs = prefs(context)
            val events = prefs.getStringSet(K_EVENTS, emptySet()).orEmpty()
            if (events.isEmpty()) return
            prefs.edit().remove(K_EVENTS).apply()
            val holdout = prefs.getBoolean(K_HOLDOUT, false)
            for (line in events) {
                val (phase, kind, outcome, value) = line.split('|').takeIf { it.size == 4 } ?: continue
                analytics.track(
                    AnalyticsEvent(
                        Events.RETURN_REMINDER,
                        EventCategory.USAGE,
                        buildMap {
                            put("phase", phase)
                            put("kind", kind)
                            // shown, withheld (the holdout) or blocked (notifications off): for a return, what
                            // had happened to the reminder it follows.
                            put("outcome", outcome)
                            put("holdout", holdout.toString())
                            if (phase == "returned") put("hours_after", (value.toLongOrNull()?.div(3_600_000L) ?: 0).toString())
                        },
                    ),
                )
            }
        }
    }

    /** What the scheduled check does: show the reminder that is due, if one is, and schedule the next check. */
    internal fun check(context: Context) {
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        forgetMissingProjects(prefs)
        val state = read(prefs)
        val nudge = ReturnNudges.due(state, now)
        if (nudge == null) {
            schedule(context, state)
            return
        }
        val holdout = prefs.getBoolean(K_HOLDOUT, false)
        val shown = !holdout && show(context, nudge, now)
        // "blocked": due, and the system would not show it (notifications are off for the app). It still counts
        // against the install's share, or an install with notifications off would be checked on forever.
        val outcome = if (holdout) "withheld" else if (shown) "shown" else "blocked"
        val edit = prefs.edit()
            .putInt(K_SENT, state.sentCount + 1)
            .putLong(K_SENT_AT, now)
            .putString(K_SENT_KIND, nudge.kind.wire)
            .putString(K_SENT_OUTCOME, outcome)
            .putStringSet(K_SENT_KEYS, state.sentKeys + nudge.key)
        queueEvent(prefs, edit, "due", nudge.kind.wire, outcome, 0)
        edit.apply()
        log.info("return reminder ${nudge.kind.wire}: $outcome")
        schedule(context, read(prefs))
    }

    private fun show(context: Context, nudge: ReturnNudge, now: Long): Boolean {
        val (title, body) = runBlocking {
            when (nudge.kind) {
                ReturnNudgeKind.FAILED_BUILD ->
                    getString(Res.string.nudge_failed_title, nudge.projectName.orEmpty()) to getString(Res.string.nudge_failed_body)
                ReturnNudgeKind.FIRST_BUILD ->
                    getString(Res.string.nudge_first_title, nudge.projectName.orEmpty()) to getString(Res.string.nudge_first_body)
                ReturnNudgeKind.UNBUILT_PROJECT ->
                    getString(Res.string.nudge_unbuilt_title, nudge.projectName.orEmpty()) to getString(Res.string.nudge_unbuilt_body)
                ReturnNudgeKind.NEVER_BUILT ->
                    getString(Res.string.nudge_never_title) to getString(Res.string.nudge_never_body)
            }
        }
        val notification = UiNotification(
            id = "$TAG-$now",
            kind = UiNotificationKind.SYSTEM,
            title = title,
            body = body,
            timestampMs = now,
            target = nudge.projectRoot?.let { UiNotificationTarget.Project(it) },
            // One tray entry for reminders, whichever it is: a second replaces the first.
            key = TAG,
        )
        if (!AndroidNotificationPresenter(context).post(notification)) return false
        // The in-app list shows it too on the next launch, as it does a push that arrived while the app was closed.
        PendingPushes.add(context, notification)
        return true
    }

    private fun schedule(context: Context, state: ReturnState) {
        val work = WorkManager.getInstance(context)
        val at = ReturnNudges.nextCheckAt(state)
        if (at == null) {
            work.cancelUniqueWork(WORK)
            return
        }
        val delay = (at - System.currentTimeMillis()).coerceAtLeast(MIN_DELAY_MS)
        work.enqueueUniqueWork(
            WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequest.Builder(CheckWorker::class.java).setInitialDelay(delay, TimeUnit.MILLISECONDS).build(),
        )
    }

    private fun read(prefs: SharedPreferences, lastUsedAt: Long? = null, enabled: Boolean? = null): ReturnState {
        val failedAt = prefs.getLong(K_FAILED_AT, 0)
        val failedRoot = prefs.getString(K_FAILED_ROOT, null)
        return ReturnState(
            installedAtMs = prefs.getLong(K_INSTALLED, System.currentTimeMillis()),
            lastUsedAtMs = lastUsedAt ?: prefs.getLong(K_USED, System.currentTimeMillis()),
            everBuilt = prefs.getBoolean(K_BUILT, false),
            failedBuild = if (failedAt > 0 && failedRoot != null) {
                FailedBuild(failedRoot, prefs.getString(K_FAILED_NAME, null).orEmpty(), failedAt)
            } else null,
            openedProject = project(prefs, K_OPENED_ROOT, K_OPENED_NAME),
            firstBuiltAtMs = prefs.getLong(K_FIRST_BUILT_AT, 0),
            builtProject = project(prefs, K_BUILT_ROOT, K_BUILT_NAME),
            sentCount = prefs.getInt(K_SENT, 0),
            lastSentAtMs = prefs.getLong(K_SENT_AT, 0),
            sentKeys = prefs.getStringSet(K_SENT_KEYS, emptySet()).orEmpty(),
            enabled = enabled ?: prefs.getBoolean(K_ENABLED, true),
        )
    }

    private fun project(prefs: SharedPreferences, rootKey: String, nameKey: String): ProjectRef? =
        prefs.getString(rootKey, null)?.let { ProjectRef(it, prefs.getString(nameKey, null).orEmpty()) }

    /** A reminder names a project and opens it, so one that was deleted since is no longer something to mention. */
    private fun forgetMissingProjects(prefs: SharedPreferences) {
        val edit = prefs.edit()
        fun gone(rootKey: String) = prefs.getString(rootKey, null)?.let { !java.io.File(it).isDirectory } == true
        if (gone(K_FAILED_ROOT)) edit.remove(K_FAILED_ROOT).remove(K_FAILED_NAME).remove(K_FAILED_AT)
        if (gone(K_OPENED_ROOT)) edit.remove(K_OPENED_ROOT).remove(K_OPENED_NAME)
        if (gone(K_BUILT_ROOT)) edit.remove(K_BUILT_ROOT).remove(K_BUILT_NAME)
        edit.commit()
    }

    private fun queueEvent(
        prefs: SharedPreferences, edit: SharedPreferences.Editor, phase: String, kind: String, outcome: String, value: Long,
    ) {
        edit.putStringSet(K_EVENTS, prefs.getStringSet(K_EVENTS, emptySet()).orEmpty() + "$phase|$kind|$outcome|$value")
    }

    private fun installTime(context: Context, fallback: Long): Long =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime }.getOrDefault(fallback)

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Runs [check] when a reminder could be due. Named in WorkManager's database, so it keeps its name. */
    class CheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result {
            runCatching { check(applicationContext) }.onFailure { log.warn("return reminder check failed", it) }
            return Result.success()
        }
    }
}
