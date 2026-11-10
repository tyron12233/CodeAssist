package dev.ide.android.e2e

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Base for the black-box feature tests. Drives the installed app (`com.tyron.code`, the `profile` variant)
 * through UI Automator only, from this test APK's own process.
 *
 * Two oracles per test:
 *  - the flow's own assertions (a label appears, console output matches, ...);
 *  - [LinkageOracle]: anything the app's processes logged during the test that looks like R8 removed or
 *    renamed something (ClassNotFoundException, NoSuchMethodError, ...), minus the known-benign probes in
 *    `assets/benign-linkage.txt`. This catches a missing keep rule even when the feature swallows the error
 *    and the UI looks fine.
 *
 * On failure the test leaves a window-hierarchy XML dump and the app's logcat in the additional-output dir
 * (text, so a failure can be read without screenshots).
 */
abstract class E2eTest {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val oracle = LinkageOracle(this::shell)

    @get:Rule
    val diagnostics = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) {
            val dir = outputDir()
            val name = "${description.testClass.simpleName}.${description.methodName}"
            runCatching { device.dumpWindowHierarchy(File(dir, "$name-window.xml")) }
            runCatching { File(dir, "$name-logcat.txt").writeText(shell("logcat -d -v threadtime")) }
        }
    }

    /**
     * First-run state written into the app's prefs file before launch, so a test starts at the project list
     * instead of the onboarding sheets ([FirstLaunchTest] walks those for real). Override to add keys.
     */
    open fun seed(): Map<String, String> = DEFAULT_SEED

    /** Wipe the app's data before this test (fresh install state). */
    open val clearAppData: Boolean = false

    /** Runs after seeding, before the app starts (e.g. install a plugin the app should discover at startup). */
    open fun beforeLaunch() {}

    private var savedProxy: String? = null

    @Before
    fun startApp() {
        // A device-wide HTTP proxy (e.g. a mitmproxy capture left on the emulator) breaks the app's own network:
        // it routes even 127.0.0.1 through the proxy and the app doesn't trust an intercepting CA, so Maven
        // resolution and the mock AI server fail. Turn it off for the test and put it back afterwards.
        val proxy = shell("settings get global http_proxy").trim()
        if (proxy.isNotEmpty() && proxy != "null" && proxy != ":0") {
            savedProxy = proxy
            shell("settings put global http_proxy :0")
        }
        shell("am force-stop $APP")
        if (clearAppData) shell("pm clear $APP")
        shell("logcat -c")
        shell("logcat -b crash -c")
        oracle.begin()
        // Skip the system detours a build would otherwise take (install permission, notification prompt).
        shell("appops set $APP REQUEST_INSTALL_PACKAGES allow")
        shell("pm grant $APP android.permission.POST_NOTIFICATIONS")
        seed().takeIf { it.isNotEmpty() }?.let(::writePrefs)
        beforeLaunch()
        launchApp()
        if (!clearAppData) goHome()
        // First-run setup on a fresh install can still overwrite the seed: if it did, seed again and relaunch.
        if (!clearAppData && seed().isNotEmpty() && !seedHeld()) {
            shell("am force-stop $APP")
            writePrefs(seed())
            launchApp()
            goHome()
        }
    }

    private fun seedHeld(): Boolean {
        val now = shell("cat /sdcard/Android/data/$APP/files/codeassist/prefs.properties")
        return seed().keys.all { it in now }
    }

    fun launchApp() {
        device.pressHome()
        val ctx = instrumentation.context
        val intent = requireNotNull(ctx.packageManager.getLaunchIntentForPackage(APP)) {
            "$APP is not installed (run through :ide-android-e2e:connectedProfileAndroidTest so AGP installs it)"
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ctx.startActivity(intent)
        check(device.wait(Until.hasObject(By.pkg(APP).depth(0)), LAUNCH_TIMEOUT)) { "$APP never came to the foreground" }
    }

    /**
     * Gets to the projects home: dismisses interruptions and closes the project the app reopened from the
     * last session (it restores the open project on launch).
     */
    fun goHome() {
        // The app shows the projects home for a moment on launch, then reopens the last project; only count
        // home once it has stayed up for a few seconds.
        var homeSince = 0L
        val home = poll(LAUNCH_TIMEOUT) {
            if (Interruptions.dismissOne(this)) return@poll null
            if (findNow("New project") != null) {
                val now = SystemClock.uptimeMillis()
                if (homeSince == 0L) homeSince = now
                return@poll if (now - homeSince >= 3_000) true else null
            }
            homeSince = 0L
            // Closing asks "Close <project>?  You'll go back to your projects." first.
            if (findNow("You'll go back to your projects.") != null && clickIfPresent("Close")) return@poll null
            device.findObject(By.desc("Close project"))?.let { it.click() }
            null
        }
        if (home == null) failWithScreen("never reached the projects home")
        Interruptions.dismissAll(this)
    }

    /**
     * Merges [entries] into the app's prefs.properties (read once at startup; the app must be stopped). The
     * test APK can't write into the app's external dir, but the shell can, so stage the file in our own
     * media dir and copy it across.
     */
    private fun writePrefs(entries: Map<String, String>) {
        val home = "/sdcard/Android/data/$APP/files/codeassist"
        val target = "$home/prefs.properties"
        // The app must create its own external dir: one the shell creates first is unwritable to the app,
        // which then falls back to internal storage and never reads the seed. So on a fresh install, launch
        // once and wait for the app to set its home up.
        // (executeShellCommand returns stdout only, so test for the path being listed, not for an error.)
        fun exists(path: String) = path in shell("ls -d $path")
        if (!exists(home)) {
            launchApp()
            poll(LAUNCH_TIMEOUT, 500) { exists(home).takeIf { it } }
                ?: error("the app never created $home")
            // Let first-run setup write its own prefs file before stopping it, or that write lands on top of
            // the seed. (seedHeld() re-checks after launch in case it still did.)
            poll(30_000, 500) { exists(target).takeIf { it } }
            SystemClock.sleep(3_000)
            shell("am force-stop $APP")
        }
        val props = java.util.Properties()
        runCatching { props.load(shell("cat $target").reader()) }
        entries.forEach { (k, v) -> props.setProperty(k, v) }
        val staged = File(instrumentation.context.externalMediaDirs.first().apply { mkdirs() }, "prefs.properties")
        staged.outputStream().use { props.store(it, "seeded by :ide-android-e2e") }
        shell("cp ${staged.absolutePath} $target")
        check(entries.keys.all { it in shell("cat $target") }) { "seeding $target did not take" }
    }

    @After
    fun checkLinkage() {
        savedProxy?.let { shell("settings put global http_proxy $it") }
        oracle.assertClean(outputDir())
    }

    // ---- shell ------------------------------------------------------------------------------------------

    fun shell(cmd: String): String = device.executeShellCommand(cmd)

    /** The on-disk directory of the project created as [name] (the app lowercases it for the folder). */
    fun projectDir(name: String): String {
        val root = "/sdcard/Android/data/$APP/files/codeassist/projects"
        val dir = shell("ls $root").lines().map { it.trim() }.firstOrNull { it.equals(name, ignoreCase = true) }
            ?: error("no project folder for $name under $root: ${shell("ls $root")}")
        return "$root/$dir"
    }

    /**
     * The contents of [path] once the shell can read it. Files the app has just created can take a moment to
     * show up to the shell, and reading one as empty must not turn into writing an edit over it.
     */
    fun readDeviceFile(path: String, timeoutMs: Long = 60_000): String =
        poll(timeoutMs, 1_000) { shell("cat $path").takeIf { it.isNotBlank() } }
            ?: error("$path never became readable: ${shell("ls -la ${path.substringBeforeLast('/')}")}")

    /**
     * Overwrites [path] (a file the APP created) with [text]: the test APK can't write into the app's storage, the
     * shell can. Never use it to create a file there: one the shell creates belongs to the shell, and the app
     * can't read it (EACCES), which fails whatever reads it, not the test.
     */
    fun writeDeviceFile(path: String, text: String) {
        check(shell("ls $path").trim() == path) { "$path doesn't exist; only overwrite files the app created" }
        val staged = File(instrumentation.context.externalMediaDirs.first().apply { mkdirs() }, "staged-${path.hashCode()}")
        staged.writeText(text)
        shell("cp ${staged.absolutePath} $path")
        check(shell("cat $path") == text) { "writing $path did not take" }
    }

    /**
     * Stops the app (so files edited behind its back with [writeDeviceFile] are read fresh), relaunches it and
     * opens the project [name] from the projects home.
     */
    fun restartAndOpen(name: String) {
        shell("am force-stop $APP")
        launchApp()
        goHome()
        clickObject(scrollTo(name))
        await("Close project", PROJECT_TIMEOUT)
        Interruptions.dismissAll(this)
    }

    fun outputDir(): File {
        val arg = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val dir = arg?.let(::File) ?: File(instrumentation.targetContext.externalMediaDirs.first(), "e2e")
        dir.mkdirs()
        return dir
    }

    // ---- finding things ---------------------------------------------------------------------------------

    /** First object whose text or content description equals [label] (Compose exposes both). */
    fun findNow(label: String): UiObject2? {
        // Compose resources wrap formatted arguments in Unicode bidi isolates (U+2066..U+2069), so "Succeeded" or
        // "Committed abc123" can arrive as "\u2068Succeeded\u2069": match with those optional.
        val p = java.util.regex.Pattern.compile("^$ISOLATES" + java.util.regex.Pattern.quote(label) + "$ISOLATES$")
        return device.findObject(By.text(p)) ?: device.findObject(By.desc(p))
    }

    /** [label] is matched against the text with invisible formatting characters and outer whitespace removed. */
    fun findNow(label: Regex): UiObject2? =
        device.findObjects(By.pkg(APP)).firstOrNull { o ->
            // A node can disappear between the query and reading it (StaleObjectException): skip it.
            runCatching { listOfNotNull(o.text, o.contentDescription) }.getOrDefault(emptyList())
                .any { label.matches(it.replace(INVISIBLE, "").trim()) }
        }

    /** Waits for [label] as text or content description; fails the test with what IS on screen if it never shows. */
    fun await(label: String, timeoutMs: Long = DEFAULT_TIMEOUT): UiObject2 =
        poll(timeoutMs) { findNow(label) } ?: failWithScreen("\"$label\" did not appear within ${timeoutMs}ms")

    fun await(label: Regex, timeoutMs: Long = DEFAULT_TIMEOUT): UiObject2 =
        poll(timeoutMs) { findNow(label) } ?: failWithScreen("/$label/ did not appear within ${timeoutMs}ms")

    fun await(selector: BySelector, timeoutMs: Long = DEFAULT_TIMEOUT): UiObject2 =
        device.wait(Until.findObject(selector), timeoutMs) ?: failWithScreen("$selector did not appear within ${timeoutMs}ms")

    /** Waits for the first of [labels] to appear and returns which one did. */
    fun awaitAny(vararg labels: String, timeoutMs: Long = DEFAULT_TIMEOUT): String =
        poll(timeoutMs) { labels.firstOrNull { findNow(it) != null } }
            ?: failWithScreen("none of ${labels.toList()} appeared within ${timeoutMs}ms")

    fun click(label: String, timeoutMs: Long = DEFAULT_TIMEOUT) = clickFresh(timeoutMs) { await(label, timeoutMs) }

    fun click(label: Regex, timeoutMs: Long = DEFAULT_TIMEOUT) = clickFresh(timeoutMs) { await(label, timeoutMs) }

    /** Clicks what [find] returns, finding it again when Compose re-renders it between the find and the click. */
    private fun clickFresh(timeoutMs: Long, find: () -> UiObject2) {
        repeat(3) { attempt ->
            try {
                return clickObject(find())
            } catch (e: androidx.test.uiautomator.StaleObjectException) {
                if (attempt == 2) throw e
                Thread.sleep(300)
            }
        }
    }

    /** Clicks the nearest clickable ancestor (Compose often puts the label on a non-clickable child). */
    fun clickObject(obj: UiObject2) {
        var target: UiObject2? = obj
        while (target != null && !target.isClickable) target = target.parent
        // A bottom-bar label sits UNDER its button's touch target, as a sibling rather than a child (the icon
        // carries the button's content description). Tapping such a label lands outside every clickable bounds
        // and does nothing, so take the clickable node it lines up with instead.
        (target?.takeIf { it.visibleBounds.width() < device.displayWidth } ?: buttonAbove(obj) ?: target ?: obj).click()
    }

    /** The clickable node horizontally over [label] and nearest above it, within a button's height. */
    private fun buttonAbove(label: UiObject2): UiObject2? {
        val b = label.visibleBounds
        return runCatching { device.findObjects(By.clickable(true).pkg(APP)) }.getOrDefault(emptyList())
            .filter { c ->
                val cb = runCatching { c.visibleBounds }.getOrNull() ?: return@filter false
                cb.width() < device.displayWidth && b.centerX() in cb.left..cb.right &&
                    cb.top <= b.top && b.top - cb.bottom < cb.height()
            }
            .minByOrNull { b.top - it.visibleBounds.bottom }
    }

    fun clickIfPresent(label: String): Boolean {
        val obj = findNow(label) ?: return false
        clickObject(obj)
        return true
    }

    fun type(into: UiObject2, text: String) {
        into.click()
        into.text = text
    }

    /** Polls [block] until it returns non-null or [timeoutMs] passes. */
    fun <T : Any> poll(timeoutMs: Long, intervalMs: Long = 250, block: () -> T?): T? {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            block()?.let { return it }
            if (SystemClock.uptimeMillis() >= end) return null
            SystemClock.sleep(intervalMs)
        }
    }

    /** All visible text and content descriptions, in tree order: the message a failed wait reports. */
    fun screenText(): String {
        val seen = LinkedHashSet<String>()
        fun walk(o: UiObject2) {
            // The UI keeps changing under a walk; a node that vanished mid-walk is skipped, not fatal.
            runCatching {
                o.text?.takeIf { it.isNotBlank() }?.let(seen::add)
                o.contentDescription?.takeIf { it.isNotBlank() }?.let { seen.add("[$it]") }
                o.children
            }.getOrNull()?.forEach(::walk)
        }
        device.findObjects(By.depth(0)).forEach(::walk)
        return seen.joinToString(" | ").take(4000)
    }

    fun failWithScreen(message: String): Nothing {
        fail("$message\nOn screen: ${screenText()}")
        throw AssertionError(message) // unreachable; fail() always throws
    }

    /** Scrolls the first scrollable container until [label] is on screen. */
    fun scrollTo(label: String, timeoutMs: Long = DEFAULT_TIMEOUT): UiObject2 {
        findNow(label)?.let { return it }
        return poll(timeoutMs) {
            findNow(label) ?: run {
                device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }
                    ?.scroll(androidx.test.uiautomator.Direction.DOWN, 0.8f)
                findNow(label)
            }
        } ?: failWithScreen("could not scroll to \"$label\"")
    }

    /**
     * The text field a label belongs to. Most fields are bare BasicTextFields with the label (or placeholder)
     * as a sibling Text, so pick the EditText closest below (or overlapping) the label.
     */
    fun fieldFor(label: String, timeoutMs: Long = DEFAULT_TIMEOUT): UiObject2 {
        val anchor = poll(timeoutMs) { findNow(label) ?: device.findObject(By.textContains(label)) }
            ?: failWithScreen("no field labelled \"$label\"")
        if (anchor.className == "android.widget.EditText") return anchor
        val a = anchor.visibleBounds
        val fields = device.findObjects(By.clazz("android.widget.EditText"))
        return fields.filter { it.visibleBounds.bottom >= a.top }
            .minByOrNull { f -> val b = f.visibleBounds; kotlin.math.abs(b.centerY() - a.centerY()) + kotlin.math.abs(b.left - a.left) / 4 }
            ?: failWithScreen("no text field near \"$label\"")
    }

    fun fill(label: String, value: String) {
        val field = fieldFor(label)
        field.click()
        field.text = value
        hideKeyboard()
    }

    /** Closes the soft keyboard if it is up (it covers the buttons below a form). */
    fun hideKeyboard() {
        if ("mInputShown=true" in shell("dumpsys input_method")) {
            device.pressBack()
            poll(3_000) { ("mInputShown=true" !in shell("dumpsys input_method")).takeIf { it } }
        }
    }


    // ---- shared IDE flows -------------------------------------------------------------------------------

    /** Projects home → New project → [template] → name it → Create Project. Returns the package name. */
    fun createProject(template: String, name: String, options: List<String> = emptyList()): String {
        click("New project")
        await("New Project")
        clickObject(scrollTo(template))
        options.forEach { click(it) }
        fill("Project name", name)
        val pkg = runCatching { fieldFor("Package name").text }.getOrNull().orEmpty()
        click("Create Project")
        // The editor's top bar carries the project name.
        await(name, PROJECT_TIMEOUT)
        return pkg
    }

    /** Projects home → account button → Settings. (On a phone the button's description is "Projects".) */
    fun openSettings() {
        clickObject(await(By.desc("Projects")))
        click("Settings and tools")
        await("Settings")
    }

    /**
     * Opens a source file through "Go to file" (on the empty editor's start list): type the name, pick the
     * result. Kotlin/Java results show without their extension.
     */
    fun openFile(fileName: String) {
        click("Go to file", PROJECT_TIMEOUT)
        val query = await(By.clazz("android.widget.EditText"))
        query.click()
        query.text = fileName
        val hit = poll(DEFAULT_TIMEOUT) {
            device.findObjects(By.text(java.util.regex.Pattern.compile("^" + Regex.escape(fileName) + "(\\.\\w+)?$")))
                // The result list re-renders as the query settles: a row can go stale between find and read.
                .firstOrNull { runCatching { it.className != "android.widget.EditText" }.getOrDefault(false) }
        } ?: failWithScreen("\"Go to file\" found no $fileName")
        clickObject(hit)
        poll(DEFAULT_TIMEOUT) { findNow("No file open").let { if (it == null) true else null } }
            ?: failWithScreen("$fileName did not open")
    }

    /** Opens the Run menu (first tap on the Run button, or the chooser chevron once a task is picked). */
    fun openRunMenu() {
        val chooser = findNow("Choose what to run")
        if (chooser != null) clickObject(chooser) else clickObject(await(Regex("^Run( .*)?$")))
    }

    fun run(task: String) {
        openRunMenu()
        click(task)
    }

    /** Waits for the build/run to settle; fails with the console text if it failed. */
    fun awaitBuildSucceeded(timeoutMs: Long = BUILD_TIMEOUT) {
        // The console's status chip ("Succeeded"/"Failed") or the phone dock's "Build succeeded"/"Build failed";
        // findNow(Regex) drops the invisible formatting characters the chip text can carry.
        val done = Regex("^(Build succeeded|Succeeded|Build failed|Failed)$")
        val hit = poll(timeoutMs) { findNow(done) } ?: failWithScreen("the build never finished")
        val text = runCatching { hit.text ?: hit.contentDescription }.getOrNull().orEmpty()
        if ("ailed" in text) {
            // Problems lists the diagnostics (a source generator's errors land here); a task that threw says why
            // in the Log tab instead.
            val problems = if (clickIfPresent("Problems")) { Thread.sleep(1_500); screenText().substringAfter("Logcat |", "") } else ""
            // The log opens at its top; its Errors filter jumps to the lines that matter.
            val log = if (clickIfPresent("Log")) {
                Thread.sleep(1_500)
                clickIfPresent("Errors")
                Thread.sleep(1_500)
                val errors = screenText().substringAfter("Logcat |", screenText())
                // A task can fail without logging an error line; the Steps tab marks which one.
                val steps = if (clickIfPresent("Steps")) {
                    Thread.sleep(1_500)
                    val top = screenText().substringAfter("Logcat |", "")
                    // The failed step is usually near the end of the list.
                    repeat(3) {
                        device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }
                            ?.scroll(androidx.test.uiautomator.Direction.DOWN, 1f)
                    }
                    Thread.sleep(800)
                    "$top ... ${screenText().substringAfter("Logcat |", "")}"
                } else ""
                "$errors || Steps: $steps"
            } else ""
            failWithScreen("build failed. Problems: ${problems.take(2_000)} || Log: ${log.take(2_000)}")
        }
    }

    fun awaitTextContains(fragment: String, timeoutMs: Long = DEFAULT_TIMEOUT): UiObject2 =
        await(By.textContains(fragment), timeoutMs)

    fun uniqueName(prefix: String) = prefix + (System.currentTimeMillis() / 1000 % 100_000)

    companion object {
        const val APP = "com.tyron.code"
        const val ISOLATES = "[\\u2066-\\u2069\\u200B-\\u200F\\u202A-\\u202E\\uFEFF\\s]*"
        /** Bidi isolates/marks, zero-width characters and BOM: invisible, but they break exact text matches. */
        val INVISIBLE = Regex("[\\u2066-\\u2069\\u200B-\\u200F\\u202A-\\u202E\\uFEFF]")
        const val DEFAULT_TIMEOUT = 20_000L
        const val LAUNCH_TIMEOUT = 60_000L
        const val PROJECT_TIMEOUT = 90_000L
        /** A cold first build on an emulator (kotlinc warm-up, dexing the template's libraries). */
        const val BUILD_TIMEOUT = 8 * 60_000L

        val DEFAULT_SEED = mapOf(
            "migration.acknowledged" to "true",
            "onboarding.seen" to "true",
            "learn.tourOffered" to "true",
            "legacy.recovery.seen" to "true",
            "analytics.consent" to "denied",
            "notif.buildPromptResolved" to "true",
            "run.conflict.alwaysStop" to "true",
            "seasonal.halloween.answeredAt" to "1",
            "settings.vcs.userName" to "E2E Bot",
            "settings.vcs.userEmail" to "e2e@example.com",
            "settings.ai.freeAiDefaultAdopted" to "true",
        )
    }
}
