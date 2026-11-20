package dev.ide.android.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Someone who opens a project, never builds it and leaves is asked about notifications while the project is
 * open, and days later is reminded of that project.
 *
 * The reminder is decided on the device and shown by a scheduled check, so the test moves the device's clock
 * forward and runs the check's job now. The clock goes back to network time whatever happens.
 */
@RunWith(AndroidJUnit4::class)
class ReturnReminderTest : E2eTest() {
    override val clearAppData = true

    // The notification prompt has not been answered, and this install is not in the group shown no reminders.
    override fun seed() = DEFAULT_SEED - "notif.buildPromptResolved" + ("reminders.holdout" to "false")

    // The harness grants the permission so builds skip the prompt; this test is about being asked.
    override fun beforeLaunch() {
        shell("pm revoke $APP $NOTIFICATIONS")
        shell("pm clear-permission-flags $APP $NOTIFICATIONS user-set user-fixed")
    }

    @Test
    fun aProjectLeftUnbuiltIsRemindedOfAfterTheUserWasAskedAboutNotifications() {
        val name = uniqueName("Left")
        createProject("Kotlin Console App", name)

        // Asked a moment into the project, with no build started.
        await("Show build notifications?", 30_000)
        click("Allow")
        val allow = device.wait(Until.findObject(By.res("com.android.permissioncontroller", "permission_allow_button")), 15_000)
            ?: failWithScreen("the system's notification prompt never came up")
        allow.click()
        poll(10_000, 500) { shell("dumpsys package $APP").takeIf { "$NOTIFICATIONS: granted=true" in it } }
            ?: failWithScreen("notifications were not granted")

        // Leave. The app records that it was in use until now and schedules its check.
        device.pressHome()
        Thread.sleep(5_000)

        val title = "Pick up where you left off in $name"
        try {
            shell("settings put global auto_time 0")
            shell("cmd alarm set-time ${System.currentTimeMillis() + 3 * DAY_MS}")
            val job = Regex("""JOB #u0a\d+/(\d+): \S+ ${Regex.escape(APP)}/androidx\.work""")
                .find(shell("dumpsys jobscheduler"))?.groupValues?.get(1)
                ?: failWithScreen("no check was scheduled when the app was left")
            shell("cmd jobscheduler run -f $APP $job")
            poll(30_000, 1_000) { shell("dumpsys notification --noredact").takeIf { title in it } }
                ?: failWithScreen("no reminder was shown: ${shell("dumpsys notification --noredact").lines().filter { APP in it }.take(5)}")
        } finally {
            shell("settings put global auto_time 1")
        }

        // The reminder opens the project it is about.
        device.openNotification()
        val entry = device.wait(Until.findObject(By.text(title)), 10_000) ?: failWithScreen("the reminder is not in the shade")
        entry.click()
        await(name, PROJECT_TIMEOUT)
        // And it is gone from the tray once the app is back in front.
        poll(10_000, 500) { shell("dumpsys notification --noredact").takeIf { title !in it } }
            ?: failWithScreen("the reminder stayed in the tray after the app was opened")
    }

    private companion object {
        const val NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
