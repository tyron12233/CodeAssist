package dev.ide.core.backend

import dev.ide.ui.backend.UiContentBlock

/**
 * The bundled Learn catalog, authored as data. [LearnBackend] maps these definitions to the UI DTOs; an
 * interactive step's [ExerciseCheck] stays here on the backend (it never crosses to the UI, so answers stay
 * authoritative). The same shape is what a remote, submission-backed lesson catalog would later produce.
 *
 * Convention for interactive exercises: the [LearnStepDef.Interactive.starterCode] and `solution` are
 * complete, compilable files in the **default package** (no `package` line) — the checker writes them to a
 * hidden scratch module's `Main` and runs it. Java exercises use a `public class Main` with a static `main`;
 * Kotlin exercises use a top-level `fun main()`.
 */
internal object LearnContent {
    /**
     * The bundled catalog's version. Bump it whenever lesson content changes: the app serves whichever of
     * the bundled and the downloaded catalog has the higher version, so a release carrying new lessons must
     * outrank the copy it downloaded before, and a published catalog must outrank the release. Format:
     * `yyyyMMddNN`.
     */
    const val VERSION: Long = 2026110403L

    val tracks: List<LearnTrackDef> = listOf(
        caEditor(), caProjects(), caBuild(), caTools(),
        kotlinBasics(), kotlinNextSteps(), kotlinOo(), kotlinCoroutines(),
        kotlinPractice().withLessons(kotlinPracticeExtra()),
        composeIntro(), composeAdvanced(),
        javaBasics(), javaBeyond(), javaMore(), javaPractice(),
        androidBasics(), androidArchitecture(),
        gettingStarted(),
    ).map { it.withLessons(trackMilestones(it.id)) }

    val paths: List<LearnPathDef> = learnPaths()

    val catalog: LearnCatalogDef get() = LearnCatalogDef(VERSION, tracks, paths)
}

/** [this] track with [extra] lessons appended (practice problems, checkpoints, capstones kept in other files). */
private fun LearnTrackDef.withLessons(extra: List<LearnLessonDef>) =
    if (extra.isEmpty()) this
    // A track ends on its milestones: lessons first, then the checkpoint, then the capstone (stable within each).
    else copy(lessons = (lessons + extra).sortedBy { MILESTONE_ORDER.indexOf(it.kind).coerceAtLeast(0) })

private val MILESTONE_ORDER = listOf("lesson", "checkpoint", "capstone")

// ---- authoring model ----

/** A whole catalog: what the bundled content builds and what a downloaded one decodes to. */
internal data class LearnCatalogDef(
    val version: Long,
    val tracks: List<LearnTrackDef>,
    val paths: List<LearnPathDef> = emptyList(),
)

internal data class LearnTrackDef(
    val id: String,
    val title: String,
    val subtitle: String,
    val iconId: String,
    val accentColor: Long?,
    val language: String,
    /** Groups tracks on the Learn screen: "Kotlin", "Java", "Android", "Get started". */
    val category: String,
    val lessons: List<LearnLessonDef>,
    /** `"Beginner"` | `"Intermediate"` | `"Advanced"`. */
    val level: String = "",
    /** Track ids recommended first. Advisory: the screen says so, nothing is locked. */
    val prerequisites: List<String> = emptyList(),
)

internal data class LearnLessonDef(
    val id: String,
    val title: String,
    val summary: String,
    val iconId: String = "docText",
    val estMinutes: Int = 5,
    val steps: List<LearnStepDef>,
    /** `"lesson"` | `"checkpoint"` | `"capstone"` (see [dev.ide.ui.backend.UiLessonKind]). */
    val kind: String = "lesson",
)

/** An ordered sequence of tracks toward one goal. */
internal data class LearnPathDef(
    val id: String,
    val title: String,
    val subtitle: String,
    val iconId: String,
    val accentColor: Long?,
    val trackIds: List<String>,
)

internal sealed interface LearnStepDef {
    val id: String
    val title: String

    data class Concept(
        override val id: String,
        override val title: String,
        val blocks: List<UiContentBlock>,
    ) : LearnStepDef

    data class Interactive(
        override val id: String,
        override val title: String,
        val blocks: List<UiContentBlock>,
        val starterCode: String,
        val language: String,
        val hints: List<String> = emptyList(),
        val solution: String,
        val check: ExerciseCheck,
        /**
         * Hands-on CodeAssist lessons (`language` `ide-*`): files written into the workspace when the step is
         * first opened (path relative to the workspace root → content). Replaces [starterCode] for them.
         */
        val files: Map<String, String> = emptyMap(),
        /** Which of [files] (or any workspace path) the editor opens for the step; null opens none. */
        val openFile: String? = null,
    ) : LearnStepDef

    data class Quiz(
        override val id: String,
        override val title: String,
        val prompt: String,
        val options: List<String>,
        val correctIndex: Int,
        val explanation: String = "",
    ) : LearnStepDef
}

/**
 * How an interactive exercise is graded from the program's captured stdout. [expectedOutput] compares the
 * whole (normalized) output; [mustContain] requires each fragment to appear; when both are empty a clean
 * exit (code 0) is enough. Normalization trims trailing whitespace and blank edges.
 */
internal data class ExerciseCheck(
    val expectedOutput: String? = null,
    val mustContain: List<String> = emptyList(),
    val caseSensitive: Boolean = true,
    /**
     * Source constructs the learner's code must actually contain (matched with whitespace removed, after
     * comments + string literals are stripped) — so an exercise can't be passed by printing the expected
     * answer as a literal. E.g. `["fun add", "add(2, 3)"]` forces defining AND calling the function.
     */
    val requireSource: List<String> = emptyList(),
    /**
     * Several runs, each fed its own stdin. When non-empty these replace [expectedOutput] and [mustContain]:
     * the program has to get every case right, so printing one memorised answer cannot pass.
     */
    val cases: List<ExerciseCase> = emptyList(),
    /** For an `"xml-layout"` exercise: the structure the layout must have. Nothing is run. */
    val layout: LayoutCheck? = null,
    /** For a hands-on CodeAssist step: what must have happened in the workspace (see [IdeCondition]). */
    val ide: List<IdeCondition> = emptyList(),
)

/** One run of a multi-case exercise: the [stdin] it is fed and what its output must be. */
internal data class ExerciseCase(
    val stdin: String,
    val expectedOutput: String? = null,
    val mustContain: List<String> = emptyList(),
)

/**
 * What an Android layout exercise must contain. Every [views] rule must match at least one element, and
 * with [cleanRender] the layout must also render through the owned engine without a problem (an unknown
 * tag or a malformed attribute fails it).
 */
internal data class LayoutCheck(
    val views: List<ViewRule>,
    val cleanRender: Boolean = true,
)

/**
 * One element the layout must contain. [tag] is the simple or qualified tag (`"Button"`); [id] is the bare id
 * name (`"signIn"` for `@+id/signIn`); [parent] is the simple tag of the direct parent; [attrs] maps attribute
 * names (`"android:orientation"`) to required values; [count] is how many such elements are needed. [label]
 * is how the learner is told about a rule that is not met ("a Button with id signIn").
 */
internal data class ViewRule(
    val tag: String,
    val label: String,
    val id: String? = null,
    val parent: String? = null,
    val attrs: Map<String, String> = emptyMap(),
    val count: Int = 1,
)

// ---- tiny authoring DSL ----

internal fun text(md: String) = UiContentBlock.Text(md.trimIndent())
internal fun code(src: String, lang: String = "kotlin") = UiContentBlock.Code(src.trimIndent(), lang)
internal fun tip(t: String) = UiContentBlock.Callout("tip", t)
internal fun note(t: String) = UiContentBlock.Callout("note", t)

/** A live, owned-rendered layout preview embedded in a lesson (see [UiContentBlock.LayoutPreview]). [xml] is a
 *  self-contained layout fragment; [interactive] gives the learner an editable field that re-renders live. */
internal fun preview(xml: String, interactive: Boolean = false, caption: String = "") =
    UiContentBlock.LayoutPreview(xml.trimIndent(), interactive, caption)

/** A live, interpreter-rendered Jetpack Compose preview embedded in a lesson (see [UiContentBlock.ComposePreview]).
 *  [code] is a self-contained Kotlin snippet (its `androidx.compose.*` imports + a `@Preview @Composable` entry);
 *  [interactive] gives the learner an editable Kotlin field that re-renders live. */
internal fun composePreview(code: String, interactive: Boolean = false, caption: String = "") =
    UiContentBlock.ComposePreview(code.trimIndent(), interactive, caption)

internal val ACCENT_KOTLIN = 0xFF7F52FFL
internal val ACCENT_JAVA = 0xFFF89820L
internal val ACCENT_START = 0xFF3FBDD9L
internal val ACCENT_KOTLIN2 = 0xFF00A8A0L
internal val ACCENT_COROUTINES = 0xFFE0533DL
internal val ACCENT_JAVA2 = 0xFFB5651DL
internal val ACCENT_KOTLIN_OO = 0xFF6D8EFFL
internal val ACCENT_PRACTICE = 0xFFE0A020L
internal val ACCENT_JAVA3 = 0xFF5382A1L
internal val ACCENT_ANDROID = 0xFF3DDC84L
internal val ACCENT_COMPOSE = 0xFF10A5A8L
internal val ACCENT_COMPOSE2 = 0xFF4285F4L
internal val ACCENT_CODEASSIST = 0xFFC6EF4CL

/** Workspace paths the CodeAssist tours seed and check (relative to the practice project's root). */
private const val TOUR_MAIN = "app/src/main/kotlin/Main.kt"
private const val TOUR_GREETER = "app/src/main/kotlin/Greeter.kt"
private const val TOUR_LAYOUT = "app/src/main/res/layout/tour.xml"

// ===========================================================================
// CodeAssist: hands-on tours of the IDE itself. Steps run in a practice project
// under the lesson banner and are checked against what actually happened there.
// ===========================================================================

private fun caEditor() = LearnTrackDef(
    id = "codeassist-editor",
    title = "CodeAssist: The editor",
    subtitle = "Quick fixes, renaming, formatting and finding your way",
    iconId = "sparkle",
    accentColor = ACCENT_CODEASSIST,
    language = "ide-kotlin",
    category = "CodeAssist",
    level = "Beginner",
    lessons = listOf(
        LearnLessonDef(
            id = "ca-ed-lightbulb", title = "Fix it with the lightbulb", summary = "Let the editor add the missing import.",
            iconId = "sparkle", estMinutes = 4,
            steps = listOf(
                LearnStepDef.Concept(
                    "ca-ed-lightbulb-c", "The editor works with you",
                    listOf(
                        text("CodeAssist understands your code as you type. Two things do most of the work:"),
                        text("• **Completion** pops up suggestions as you type. Pick one to insert it, imports and all.\n• The **lightbulb** appears beside a problem. Tap it to see fixes: add an import, add a library, create a missing function."),
                        tip("Red underlines are errors, yellow are warnings. Long-press one to see what it says."),
                    ),
                ),
                LearnStepDef.Interactive(
                    "ca-ed-lightbulb-i", "Import LocalDate",
                    listOf(
                        text("`LocalDate` is underlined in red: the file doesn't import it. Put the cursor on `LocalDate`, tap the **lightbulb**, and choose **Import**."),
                        text("Then press **Check**."),
                    ),
                    starterCode = "",
                    language = "ide-kotlin",
                    hints = listOf(
                        "Tap anywhere inside the word LocalDate so the cursor is on it.",
                        "The lightbulb shows at the left of the line. Pick \"Import java.time.LocalDate\".",
                    ),
                    solution = "",
                    files = mapOf(
                        TOUR_MAIN to """
                            fun main() {
                                val today = LocalDate.now()
                                println("Year: ${'$'}{today.year}")
                            }
                        """,
                    ),
                    openFile = TOUR_MAIN,
                    check = ExerciseCheck(
                        ide = listOf(
                            IdeCondition.FileContains(TOUR_MAIN, "import java.time.LocalDate", "LocalDate is imported"),
                            IdeCondition.Compiles("The project compiles"),
                        ),
                    ),
                ),
                LearnStepDef.Quiz(
                    "ca-ed-lightbulb-q", "Quick check",
                    prompt = "Where do you find the fixes for a red-underlined problem?",
                    options = listOf("In Settings", "Behind the lightbulb", "In the Run console", "Nowhere, you fix it by hand"),
                    correctIndex = 1,
                    explanation = "The lightbulb offers fixes for the problem under the cursor.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "ca-ed-rename", title = "Rename everywhere at once", summary = "Change a name in every file safely.",
            iconId = "sparkle", estMinutes = 5,
            steps = listOf(
                LearnStepDef.Concept(
                    "ca-ed-rename-c", "Navigate and refactor",
                    listOf(
                        text("Long-press a name to reach the code actions:"),
                        text("• **Go to Declaration** jumps to where it is defined.\n• **Find Usages** lists every place it is used.\n• **Rename** changes it everywhere, across files, without touching unrelated text."),
                    ),
                ),
                LearnStepDef.Interactive(
                    "ca-ed-rename-i", "Rename greet to welcome",
                    listOf(
                        text("`greet` is defined in **Greeter.kt** and used twice in **Main.kt**. Long-press `greet`, choose **Rename**, and call it `welcome`."),
                        tip("Editing it by hand would mean finding every use yourself. Rename does all of them."),
                    ),
                    starterCode = "",
                    language = "ide-kotlin",
                    hints = listOf(
                        "Long-press the word greet in Main.kt, then pick Rename from the menu.",
                        "Type welcome and confirm. Both files change.",
                    ),
                    solution = "",
                    files = mapOf(
                        TOUR_GREETER to """
                            fun greet(name: String) = "Hello, ${'$'}name!"
                        """,
                        TOUR_MAIN to """
                            fun main() {
                                println(greet("Ana"))
                                println(greet("Bo"))
                            }
                        """,
                    ),
                    openFile = TOUR_MAIN,
                    check = ExerciseCheck(
                        ide = listOf(
                            IdeCondition.FileContains(TOUR_GREETER, "fun welcome(", "Greeter.kt defines welcome"),
                            IdeCondition.FileLacks(TOUR_MAIN, "greet(", "Main.kt no longer calls greet"),
                            IdeCondition.FileLacks(TOUR_GREETER, "greet(", "Greeter.kt no longer defines greet"),
                            IdeCondition.Compiles("The project compiles"),
                        ),
                    ),
                ),
            ),
        ),
        LearnLessonDef(
            id = "ca-ed-format", title = "Tidy up with Format", summary = "One action fixes messy indentation.",
            iconId = "sparkle", estMinutes = 3,
            steps = listOf(
                LearnStepDef.Interactive(
                    "ca-ed-format-i", "Format the file",
                    listOf(
                        text("This code works, but its indentation is a mess. Open the editor's **⋯** menu and choose **Reformat code**."),
                        note("Formatting follows your Code style settings, which you can change in Settings."),
                    ),
                    starterCode = "",
                    language = "ide-kotlin",
                    hints = listOf("The ⋯ in the editor's top bar has Reformat code."),
                    solution = "",
                    files = mapOf(
                        TOUR_MAIN to """
                            fun main() {
                            println("one")
                                    println("two")
                            }
                        """,
                    ),
                    openFile = TOUR_MAIN,
                    check = ExerciseCheck(
                        ide = listOf(
                            IdeCondition.FileContains(TOUR_MAIN, "\n    println(\"one\")\n    println(\"two\")", "Both lines are indented by four spaces"),
                        ),
                    ),
                ),
                LearnStepDef.Concept(
                    "ca-ed-format-more", "More tools worth knowing",
                    listOf(
                        text("• **Bookmarks** mark a line to come back to; a **TODO** comment shows up in the TODO list.\n• **Local history** keeps earlier saved versions of a file, so you can go back even without Git.\n• **Zen mode** hides everything but the code."),
                    ),
                ),
                LearnStepDef.Quiz(
                    "ca-ed-format-q", "Quick check",
                    prompt = "You saved a file an hour ago and want the old version back, and the project has no Git. Where do you look?",
                    options = listOf("Local history", "The Run console", "Find Usages", "It's gone"),
                    correctIndex = 0,
                    explanation = "Local history keeps the versions you saved.",
                ),
            ),
        ),
    ),
)

private fun caProjects() = LearnTrackDef(
    id = "codeassist-projects",
    title = "CodeAssist: Projects & libraries",
    subtitle = "Templates, modules and adding libraries",
    iconId = "module",
    accentColor = ACCENT_CODEASSIST,
    language = "ide-kotlin",
    category = "CodeAssist",
    level = "Beginner",
    prerequisites = listOf("codeassist-editor"),
    lessons = listOf(
        LearnLessonDef(
            id = "ca-pr-projects", title = "Projects and modules", summary = "How a CodeAssist project is put together.",
            iconId = "module", estMinutes = 4,
            steps = listOf(
                LearnStepDef.Concept(
                    "ca-pr-projects-c", "Start from a template",
                    listOf(
                        text("**New project** offers templates: a console app, an Android app with Views or Compose, a library, Kotlin Multiplatform, and more. Each one builds and runs straight away."),
                        text("A project holds one or more **modules**. An app module builds your app; library modules hold shared code. Open the project menu to add modules or change a module's settings."),
                        tip("Already have a project from Android Studio? **Import** opens Gradle projects and zips."),
                    ),
                ),
                LearnStepDef.Quiz(
                    "ca-pr-projects-q", "Quick check",
                    prompt = "You have a Gradle project from Android Studio. How do you open it in CodeAssist?",
                    options = listOf("Retype it", "Import it", "Only through Git", "You can't"),
                    correctIndex = 1,
                    explanation = "Import reads the Gradle build and creates the CodeAssist project for you.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "ca-pr-library", title = "Add a library", summary = "Use code other people wrote.",
            iconId = "module", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Interactive(
                    "ca-pr-library-i", "Add Gson",
                    listOf(
                        text("This program turns an object into JSON with the **Gson** library, which the project doesn't have yet. Add it: tap the lightbulb on `Gson` and choose **Add library**, or open **Dependencies** from the project menu and search for gson."),
                        text("When it resolves, press **Check**."),
                    ),
                    starterCode = "",
                    language = "ide-kotlin",
                    hints = listOf(
                        "The lightbulb on the red Gson import offers to add the library for you.",
                        "Or: project menu, Dependencies, search \"gson\", add com.google.code.gson:gson.",
                    ),
                    solution = "",
                    files = mapOf(
                        TOUR_MAIN to """
                            import com.google.gson.Gson

                            data class Score(val name: String, val points: Int)

                            fun main() {
                                println(Gson().toJson(Score("Ana", 92)))
                            }
                        """,
                    ),
                    openFile = TOUR_MAIN,
                    check = ExerciseCheck(
                        ide = listOf(
                            IdeCondition.Dependency("com.google.code.gson:gson", "Gson is a dependency of the project"),
                            IdeCondition.Compiles("The project compiles"),
                        ),
                    ),
                ),
                LearnStepDef.Quiz(
                    "ca-pr-library-q", "Quick check",
                    prompt = "Where do libraries come from when you add them?",
                    options = listOf("They're bundled in the app", "Maven repositories, downloaded once and cached", "You copy jar files by hand", "The Store"),
                    correctIndex = 1,
                    explanation = "CodeAssist resolves them from Maven repositories and caches them, so they work offline afterwards.",
                ),
            ),
        ),
    ),
)

private fun caBuild() = LearnTrackDef(
    id = "codeassist-build",
    title = "CodeAssist: Build, run & ship",
    subtitle = "Running, signing and building an APK",
    iconId = "hammer",
    accentColor = ACCENT_CODEASSIST,
    language = "ide-kotlin",
    category = "CodeAssist",
    level = "Intermediate",
    prerequisites = listOf("codeassist-projects"),
    lessons = listOf(
        LearnLessonDef(
            id = "ca-bu-run", title = "Run and talk to your program", summary = "Type input into a running program.",
            iconId = "hammer", estMinutes = 4,
            steps = listOf(
                LearnStepDef.Interactive(
                    "ca-bu-run-i", "Run it",
                    listOf(
                        text("This program asks for your name. Press **Run** (the ▶ in the top bar). When the console asks, type your name and press Enter."),
                        tip("The console is a real terminal: anything the program reads with readln() comes from what you type."),
                    ),
                    starterCode = "",
                    language = "ide-kotlin",
                    hints = listOf("The Run button is the green ▶ at the top of the editor.", "Type into the box at the bottom of the console, then Enter."),
                    solution = "",
                    files = mapOf(
                        TOUR_MAIN to """
                            fun main() {
                                print("Your name? ")
                                val name = readln()
                                println("Hello, ${'$'}name!")
                            }
                        """,
                    ),
                    openFile = TOUR_MAIN,
                    check = ExerciseCheck(
                        ide = listOf(IdeCondition.RunOutput("Hello, ", "You ran it and answered")),
                    ),
                ),
            ),
        ),
        LearnLessonDef(
            id = "ca-bu-sign", title = "Make a signing key", summary = "Every released Android app is signed.",
            iconId = "hammer", estMinutes = 4,
            steps = listOf(
                LearnStepDef.Concept(
                    "ca-bu-sign-c", "Why apps are signed",
                    listOf(
                        text("Android only installs apps that are **signed**. Debug builds use a debug key automatically. To release an app, and to update it later, you sign it with **your own key**, kept in a keystore."),
                        note("Keep your keystore and its passwords safe. An app signed with one key can only be updated with the same key."),
                    ),
                ),
                LearnStepDef.Interactive(
                    "ca-bu-sign-i", "Create a keystore",
                    listOf(text("Open **Settings & tools** from the project menu, then **Keystores**, and create a new key. Any name and password will do for practice.")),
                    starterCode = "",
                    language = "ide-kotlin",
                    hints = listOf("Project menu, Settings & tools, Keystores, then Create."),
                    solution = "",
                    files = mapOf(
                        TOUR_MAIN to """
                            fun main() {
                                println("Hello, CodeAssist!")
                            }
                        """,
                    ),
                    openFile = TOUR_MAIN,
                    check = ExerciseCheck(ide = listOf(IdeCondition.Keystore("A keystore exists"))),
                ),
            ),
        ),
        LearnLessonDef(
            id = "ca-bu-apk", title = "Build an APK", summary = "Turn the project into an installable app.",
            iconId = "hammer", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Interactive(
                    "ca-bu-apk-i", "Build it",
                    listOf(
                        text("This is a small Android app. Build an installable **APK** from the **Run** menu (the arrow next to ▶), with **Build APK**."),
                        note("The first build takes a while: it prepares the Android libraries once. Later builds are much faster."),
                    ),
                    starterCode = "",
                    language = "ide-android",
                    hints = listOf("Tap the small arrow beside the Run button for the build options."),
                    solution = "",
                    files = emptyMap(),
                    openFile = "app/src/main/AndroidManifest.xml",
                    check = ExerciseCheck(ide = listOf(IdeCondition.ApkBuilt("An APK was built"))),
                ),
                LearnStepDef.Concept(
                    "ca-bu-apk-share", "Ship it",
                    listOf(
                        text("From here you can install the APK, share it, or publish your project to the **Store**, where other CodeAssist users can open it and learn from it."),
                    ),
                ),
            ),
        ),
    ),
)

private fun caTools() = LearnTrackDef(
    id = "codeassist-tools",
    title = "CodeAssist: Previews, Git & AI",
    subtitle = "See layouts live, save history with Git, get help from the AI",
    iconId = "layers",
    accentColor = ACCENT_CODEASSIST,
    language = "ide-kotlin",
    category = "CodeAssist",
    level = "Intermediate",
    prerequisites = listOf("codeassist-editor"),
    lessons = listOf(
        LearnLessonDef(
            id = "ca-to-preview", title = "See your layout live", summary = "Edit XML and watch it render.",
            iconId = "layers", estMinutes = 4,
            steps = listOf(
                LearnStepDef.Interactive(
                    "ca-to-preview-i", "Change the greeting",
                    listOf(
                        text("Open the **split view** (the icon at the right of the editor's toolbar) to see this layout rendered beside the XML. Then change the TextView's text to `Hello, preview` and watch the preview update."),
                        tip("Compose files get a live preview too: any function marked @Preview."),
                    ),
                    starterCode = "",
                    language = "ide-android",
                    hints = listOf("The split icon is next to the code icon above the editor.", "Edit android:text, keeping the quotes."),
                    solution = "",
                    files = mapOf(
                        TOUR_LAYOUT to """
                            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                                android:layout_width="match_parent"
                                android:layout_height="match_parent"
                                android:orientation="vertical"
                                android:padding="24dp">

                                <TextView
                                    android:layout_width="wrap_content"
                                    android:layout_height="wrap_content"
                                    android:text="Hello"
                                    android:textSize="24sp" />

                            </LinearLayout>
                        """,
                    ),
                    openFile = TOUR_LAYOUT,
                    check = ExerciseCheck(
                        ide = listOf(IdeCondition.FileContains(TOUR_LAYOUT, "android:text=\"Hello, preview\"", "The text says Hello, preview")),
                    ),
                ),
            ),
        ),
        LearnLessonDef(
            id = "ca-to-git", title = "Your first commit", summary = "Save a snapshot of your work with Git.",
            iconId = "layers", estMinutes = 5,
            steps = listOf(
                LearnStepDef.Concept(
                    "ca-to-git-c", "Why Git",
                    listOf(
                        text("**Git** records snapshots of your project called **commits**. You can see what changed, go back, and push to GitHub to back it up or share it."),
                    ),
                ),
                LearnStepDef.Interactive(
                    "ca-to-git-i", "Commit",
                    listOf(text("Open **Source** from the bottom bar. If the project isn't a repository yet, create one. Then write a message like `First commit` and **Commit**.")),
                    starterCode = "",
                    language = "ide-kotlin",
                    hints = listOf("Source is in the bottom bar.", "Initialize the repository first, then stage the files and commit."),
                    solution = "",
                    files = mapOf(
                        TOUR_MAIN to """
                            fun main() {
                                println("Hello, CodeAssist!")
                            }
                        """,
                    ),
                    openFile = TOUR_MAIN,
                    check = ExerciseCheck(ide = listOf(IdeCondition.GitCommit("You made a commit"))),
                ),
            ),
        ),
        LearnLessonDef(
            id = "ca-to-ai", title = "Ask the AI", summary = "Get help explaining and changing code.",
            iconId = "sparkle", estMinutes = 3,
            steps = listOf(
                LearnStepDef.Concept(
                    "ca-to-ai-c", "The AI agent",
                    listOf(
                        text("The **AI** tab can explain code, fix errors, and make changes across your project. It shows you each change before it is applied."),
                        text("Use the free tier, or connect your own provider in Settings."),
                        tip("While learning, ask it to explain rather than to write the answer: you will remember it."),
                    ),
                ),
                LearnStepDef.Quiz(
                    "ca-to-ai-q", "Quick check",
                    prompt = "What's the best way to use the AI while doing a lesson?",
                    options = listOf("Ask it to write the answer", "Ask it to explain what you don't understand", "Don't use it at all", "Ask it to pass the check"),
                    correctIndex = 1,
                    explanation = "Explanations help you learn. The exercises only teach if you do them.",
                ),
            ),
        ),
    ),
)

// ===========================================================================
// Paths
// ===========================================================================

/** Ordered goals across tracks. Each id must name a track in [LearnContent.tracks] (LearnContentTest). */
private fun learnPaths() = listOf(
    LearnPathDef(
        id = "path-codeassist",
        title = "Master CodeAssist",
        subtitle = "The editor, libraries, builds, previews and Git, hands on",
        iconId = "sparkle",
        accentColor = ACCENT_CODEASSIST,
        trackIds = listOf("codeassist-editor", "codeassist-projects", "codeassist-build", "codeassist-tools"),
    ),
    LearnPathDef(
        id = "path-android",
        title = "Android developer",
        subtitle = "From your first line of Kotlin to Compose apps",
        iconId = "android",
        accentColor = ACCENT_ANDROID,
        trackIds = listOf(
            "getting-started", "kotlin-basics", "kotlin-next", "kotlin-oo",
            "android-basics", "compose-intro", "compose-advanced", "android-architecture",
        ),
    ),
    LearnPathDef(
        id = "path-kotlin",
        title = "Kotlin in depth",
        subtitle = "The language end to end, up to coroutines",
        iconId = "kotlin",
        accentColor = ACCENT_KOTLIN,
        trackIds = listOf("kotlin-basics", "kotlin-next", "kotlin-oo", "kotlin-practice", "kotlin-coroutines"),
    ),
    LearnPathDef(
        id = "path-java",
        title = "Java foundations",
        subtitle = "Classes, collections and generics in Java",
        iconId = "coffee",
        accentColor = ACCENT_JAVA,
        trackIds = listOf("getting-started", "java-basics", "java-beyond", "java-more", "java-practice"),
    ),
)

// ===========================================================================
// Kotlin Basics
// ===========================================================================

private fun kotlinBasics() = LearnTrackDef(
    id = "kotlin-basics",
    title = "Kotlin Basics",
    subtitle = "Write your first Kotlin, one small step at a time",
    iconId = "kotlin",
    accentColor = ACCENT_KOTLIN,
    language = "kotlin",
    category = "Kotlin",
    level = "Beginner",
    lessons = listOf(
        LearnLessonDef(
            id = "kt-hello", title = "Hello, Kotlin", summary = "Print your very first line of output.",
            iconId = "kotlin", estMinutes = 4,
            steps = listOf(
                LearnStepDef.Concept(
                    "kt-hello-c", "The main function",
                    listOf(
                        text("Every Kotlin program starts running from a special function called **main**."),
                        code(
                            """
                            fun main() {
                                println("Hello, Kotlin!")
                            }
                            """
                        ),
                        text("`fun` starts a function, `main` is its name, and the `{ ... }` holds the code that runs. `println(...)` **prints a line** of text to the console."),
                        tip("The text inside the double quotes is called a *string*. You can print any string you like."),
                    ),
                ),
                LearnStepDef.Interactive(
                    "kt-hello-i", "Print a greeting",
                    listOf(text("Your turn. Make the program print exactly:\n\n`Hello, Kotlin!`\n\nEdit the code, then tap **Run & Check**.")),
                    starterCode = """
                        fun main() {
                            // Print Hello, Kotlin! below
                        }
                    """,
                    language = "kotlin",
                    hints = listOf(
                        "Use println(...) inside main.",
                        "Put the exact text in double quotes: println(\"Hello, Kotlin!\")",
                    ),
                    solution = """
                        fun main() {
                            println("Hello, Kotlin!")
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "Hello, Kotlin!", requireSource = listOf("println(")),
                ),
                LearnStepDef.Quiz(
                    "kt-hello-q", "Quick check",
                    prompt = "Which function prints a line of text to the console?",
                    options = listOf("read()", "println()", "printline()", "console()"),
                    correctIndex = 1,
                    explanation = "println() prints its argument followed by a new line.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "kt-vars", title = "Values and variables", summary = "Store data with val and var.",
            iconId = "kotlin", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "kt-vars-c", "val vs var",
                    listOf(
                        text("A **value** gives a name to some data. Kotlin has two kinds:"),
                        code(
                            """
                            val name = "Sam"   // read-only: can't be reassigned
                            var count = 0      // mutable: can change later
                            count = count + 1
                            """
                        ),
                        text("Prefer `val` — it makes code easier to reason about. Reach for `var` only when a value truly needs to change."),
                        text("You can join strings together with `+`:"),
                        code("""println("Hi, " + name + "!")"""),
                    ),
                ),
                LearnStepDef.Interactive(
                    "kt-vars-i", "Greet by name",
                    listOf(text("Create a read-only value `name` set to `Sam`, then print:\n\n`Hi, Sam!`")),
                    starterCode = """
                        fun main() {
                            // 1. Declare a val called name set to "Sam"
                            // 2. Print "Hi, Sam!" using that value
                        }
                    """,
                    language = "kotlin",
                    hints = listOf(
                        "Declare it: val name = \"Sam\"",
                        "Build the greeting: println(\"Hi, \" + name + \"!\")",
                    ),
                    solution = """
                        fun main() {
                            val name = "Sam"
                            println("Hi, " + name + "!")
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "Hi, Sam!", requireSource = listOf("val name", "println(")),
                ),
                LearnStepDef.Quiz(
                    "kt-vars-q", "Quick check",
                    prompt = "Which keyword declares a value that cannot be reassigned?",
                    options = listOf("var", "val", "let", "const"),
                    correctIndex = 1,
                    explanation = "val is read-only. var can be reassigned.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "kt-funcs", title = "Functions", summary = "Package logic you can reuse.",
            iconId = "kotlin", estMinutes = 7,
            steps = listOf(
                LearnStepDef.Concept(
                    "kt-funcs-c", "Declaring a function",
                    listOf(
                        text("A function takes inputs (**parameters**) and can **return** a result:"),
                        code(
                            """
                            fun add(a: Int, b: Int): Int {
                                return a + b
                            }
                            """
                        ),
                        text("`a: Int` is a parameter of type `Int`. The `: Int` after the parentheses is the **return type** — what the function gives back."),
                        tip("Call a function by its name: add(2, 3) evaluates to 5."),
                    ),
                ),
                LearnStepDef.Interactive(
                    "kt-funcs-i", "Write add()",
                    listOf(text("Write a function `add` that returns the sum of two `Int`s, then print `add(2, 3)` (which should be `5`).")),
                    starterCode = """
                        // Define your add function here

                        fun main() {
                            // Print the result of add(2, 3)
                        }
                    """,
                    language = "kotlin",
                    hints = listOf(
                        "fun add(a: Int, b: Int): Int { return a + b }",
                        "Then: println(add(2, 3))",
                    ),
                    solution = """
                        fun add(a: Int, b: Int): Int {
                            return a + b
                        }

                        fun main() {
                            println(add(2, 3))
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "5", requireSource = listOf("fun add", "add(2, 3)")),
                ),
            ),
        ),
        LearnLessonDef(
            id = "kt-if", title = "Making decisions", summary = "Branch with if / else.",
            iconId = "kotlin", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "kt-if-c", "if / else",
                    listOf(
                        text("`if` runs code only when a condition is true; `else` covers the other case:"),
                        code(
                            """
                            val n = 42
                            if (n > 10) {
                                println("big")
                            } else {
                                println("small")
                            }
                            """
                        ),
                        text("Conditions use comparisons like `>` (greater than), `<` (less than), and `==` (equal to)."),
                    ),
                ),
                LearnStepDef.Interactive(
                    "kt-if-i", "Big or small?",
                    listOf(text("`n` is `42`. Print `big` when `n` is greater than `10`, otherwise print `small`.")),
                    starterCode = """
                        fun main() {
                            val n = 42
                            // Print "big" if n > 10, else "small"
                        }
                    """,
                    language = "kotlin",
                    hints = listOf(
                        "Start with: if (n > 10) { ... } else { ... }",
                        "Print inside each branch with println(\"big\") / println(\"small\").",
                    ),
                    solution = """
                        fun main() {
                            val n = 42
                            if (n > 10) {
                                println("big")
                            } else {
                                println("small")
                            }
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "big", requireSource = listOf("if (")),
                ),
                LearnStepDef.Quiz(
                    "kt-if-q", "Quick check",
                    prompt = "Which operator tests whether two values are equal?",
                    options = listOf("=", "==", "=>", "equals"),
                    correctIndex = 1,
                    explanation = "A single = assigns; == compares for equality.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "kt-loops", title = "Repeating with loops", summary = "Do something many times.",
            iconId = "kotlin", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "kt-loops-c", "for and ranges",
                    listOf(
                        text("A `for` loop repeats once for each item. A **range** like `1..5` is the numbers 1, 2, 3, 4, 5:"),
                        code(
                            """
                            for (i in 1..5) {
                                println(i)
                            }
                            """
                        ),
                        text("Each time around, `i` takes the next value in the range."),
                    ),
                ),
                LearnStepDef.Interactive(
                    "kt-loops-i", "Count to five",
                    listOf(text("Print the numbers `1` to `5`, each on its own line.")),
                    starterCode = """
                        fun main() {
                            // Loop from 1 to 5 and print each number
                        }
                    """,
                    language = "kotlin",
                    hints = listOf(
                        "Use a range: for (i in 1..5) { ... }",
                        "Print the loop variable: println(i)",
                    ),
                    solution = """
                        fun main() {
                            for (i in 1..5) {
                                println(i)
                            }
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "1\n2\n3\n4\n5", requireSource = listOf("for (")),
                ),
                LearnStepDef.Quiz(
                    "kt-loops-q", "Quick check",
                    prompt = "What does the range 1..5 include?",
                    options = listOf("1, 2, 3, 4", "1, 2, 3, 4, 5", "0, 1, 2, 3, 4, 5", "only 1 and 5"),
                    correctIndex = 1,
                    explanation = "1..5 is inclusive on both ends: 1, 2, 3, 4, 5.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "kt-basics-checkpoint", title = "Kotlin Basics review", kind = "checkpoint",
            summary = "Five questions across the whole track. Aim to get them right first time.",
            iconId = "check", estMinutes = 4,
            steps = listOf(
                LearnStepDef.Quiz(
                    "kt-cp-q1", "Question 1",
                    prompt = "Which declaration can be given a new value later?",
                    options = listOf("val count = 1", "var count = 1", "const val COUNT = 1", "fun count() = 1"),
                    correctIndex = 1,
                    explanation = "`var` is reassignable. `val` is read-only once set.",
                ),
                LearnStepDef.Quiz(
                    "kt-cp-q2", "Question 2",
                    prompt = "What does this print?\n\nval n = 7\nprintln(if (n % 2 == 0) \"even\" else \"odd\")",
                    options = listOf("even", "odd", "7", "Nothing, it doesn't compile"),
                    correctIndex = 1,
                    explanation = "7 % 2 is 1, so the condition is false and the else branch, \"odd\", is the value.",
                ),
                LearnStepDef.Quiz(
                    "kt-cp-q3", "Question 3",
                    prompt = "How many times does `for (i in 1..4)` run its body?",
                    options = listOf("3", "4", "5", "It runs forever"),
                    correctIndex = 1,
                    explanation = "1..4 includes both ends: 1, 2, 3 and 4.",
                ),
                LearnStepDef.Quiz(
                    "kt-cp-q4", "Question 4",
                    prompt = "What type does `fun add(a: Int, b: Int) = a + b` return?",
                    options = listOf("Unit", "Int", "String", "Any"),
                    correctIndex = 1,
                    explanation = "An expression body's return type is inferred from the expression: Int + Int is Int.",
                ),
                LearnStepDef.Quiz(
                    "kt-cp-q5", "Question 5",
                    prompt = "With `val name = \"Ana\"`, which line prints `Hi, Ana!`?",
                    options = listOf("println(\"Hi, \$name!\")", "println(\"Hi, name!\")", "println(Hi, name)", "print[\"Hi, \" + name]"),
                    correctIndex = 0,
                    explanation = "A `\$name` template inside a string is replaced by the variable's value.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "kt-basics-capstone", title = "Score report", kind = "capstone",
            summary = "Read real input, loop over it, and report on it.",
            iconId = "kotlin", estMinutes = 12,
            steps = listOf(
                LearnStepDef.Concept(
                    "kt-cap-c", "Reading input",
                    listOf(
                        text("So far your programs printed fixed values. Real programs read **input**. `readln()` reads one line of text, and `toInt()` turns it into a number:"),
                        code(
                            """
                            val count = readln().toInt()
                            for (i in 1..count) {
                                val line = readln()
                                println("Line ${'$'}i was ${'$'}line")
                            }
                            """
                        ),
                        tip("This exercise is checked against several different inputs, so the program has to really work it out every time."),
                    ),
                ),
                LearnStepDef.Interactive(
                    "kt-cap-i", "Score report",
                    listOf(
                        text("The first line of input is how many scores follow. Each score is on its own line. Print three lines:"),
                        code(
                            """
                            Highest: 92
                            Lowest: 40
                            Passed: 2 of 3
                            """,
                            "plain",
                        ),
                        text("A score **passes** when it is 50 or more. For the input `3`, `70`, `92`, `40` the output is exactly the example above."),
                    ),
                    starterCode = """
                        fun main() {
                            val count = readln().toInt()
                            // Read `count` scores, one per line, then print:
                            // Highest: <the biggest score>
                            // Lowest: <the smallest score>
                            // Passed: <how many are 50 or more> of <count>
                        }
                    """,
                    language = "kotlin",
                    hints = listOf(
                        "Keep three variables while you loop: the highest so far, the lowest so far, and a pass counter.",
                        "Start highest at Int.MIN_VALUE and lowest at Int.MAX_VALUE, so the first score replaces both.",
                        "Inside for (i in 1..count), read a score with readln().trim().toInt() and compare it.",
                    ),
                    solution = """
                        fun main() {
                            val count = readln().toInt()
                            var highest = Int.MIN_VALUE
                            var lowest = Int.MAX_VALUE
                            var passed = 0
                            for (i in 1..count) {
                                val score = readln().trim().toInt()
                                if (score > highest) highest = score
                                if (score < lowest) lowest = score
                                if (score >= 50) passed++
                            }
                            println("Highest: ${'$'}highest")
                            println("Lowest: ${'$'}lowest")
                            println("Passed: ${'$'}passed of ${'$'}count")
                        }
                    """,
                    check = ExerciseCheck(
                        cases = listOf(
                            ExerciseCase("3\n70\n92\n40\n", "Highest: 92\nLowest: 40\nPassed: 2 of 3"),
                            ExerciseCase("1\n50\n", "Highest: 50\nLowest: 50\nPassed: 1 of 1"),
                            ExerciseCase("4\n10\n49\n30\n20\n", "Highest: 49\nLowest: 10\nPassed: 0 of 4"),
                            ExerciseCase("5\n100\n99\n51\n50\n77\n", "Highest: 100\nLowest: 50\nPassed: 5 of 5"),
                        ),
                    ),
                ),
            ),
        ),
    ),
)

// ===========================================================================
// Java Basics
// ===========================================================================

private fun javaBasics() = LearnTrackDef(
    id = "java-basics",
    title = "Java Basics",
    subtitle = "The fundamentals of Java, hands-on",
    iconId = "java",
    accentColor = ACCENT_JAVA,
    language = "java",
    category = "Java",
    level = "Beginner",
    lessons = listOf(
        LearnLessonDef(
            id = "java-hello", title = "Hello, Java", summary = "Your first Java program.",
            iconId = "java", estMinutes = 4,
            steps = listOf(
                LearnStepDef.Concept(
                    "java-hello-c", "The main method",
                    listOf(
                        text("A Java program runs from a **main method** inside a class:"),
                        code(
                            """
                            public class Main {
                                public static void main(String[] args) {
                                    System.out.println("Hello, Java!");
                                }
                            }
                            """,
                            "java",
                        ),
                        text("`System.out.println(...)` prints a line of text. Notice each statement ends with a semicolon `;`."),
                    ),
                ),
                LearnStepDef.Interactive(
                    "java-hello-i", "Print a greeting",
                    listOf(text("Make the program print exactly:\n\n`Hello, Java!`")),
                    starterCode = """
                        public class Main {
                            public static void main(String[] args) {
                                // Print Hello, Java! below
                            }
                        }
                    """,
                    language = "java",
                    hints = listOf(
                        "Use System.out.println(...);",
                        "Match the text exactly: System.out.println(\"Hello, Java!\");",
                    ),
                    solution = """
                        public class Main {
                            public static void main(String[] args) {
                                System.out.println("Hello, Java!");
                            }
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "Hello, Java!", requireSource = listOf("System.out.println(")),
                ),
                LearnStepDef.Quiz(
                    "java-hello-q", "Quick check",
                    prompt = "What must every Java statement end with?",
                    options = listOf("a period .", "a semicolon ;", "a comma ,", "nothing"),
                    correctIndex = 1,
                    explanation = "Java statements end with a semicolon.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "java-vars", title = "Variables and types", summary = "Store numbers and text.",
            iconId = "java", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "java-vars-c", "Declaring variables",
                    listOf(
                        text("In Java you state a variable's **type** when you declare it:"),
                        code(
                            """
                            int count = 7;
                            String name = "Sam";
                            System.out.println("Count: " + count);
                            """,
                            "java",
                        ),
                        text("`int` holds whole numbers; `String` holds text. You can join text and numbers with `+`."),
                    ),
                ),
                LearnStepDef.Interactive(
                    "java-vars-i", "Print a count",
                    listOf(text("Store `7` in an `int` called `count`, then print:\n\n`Count: 7`")),
                    starterCode = """
                        public class Main {
                            public static void main(String[] args) {
                                // 1. Declare an int count = 7
                                // 2. Print "Count: 7"
                            }
                        }
                    """,
                    language = "java",
                    hints = listOf(
                        "Declare it: int count = 7;",
                        "Print it: System.out.println(\"Count: \" + count);",
                    ),
                    solution = """
                        public class Main {
                            public static void main(String[] args) {
                                int count = 7;
                                System.out.println("Count: " + count);
                            }
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "Count: 7", requireSource = listOf("int count", "System.out.println(")),
                ),
            ),
        ),
        LearnLessonDef(
            id = "java-methods", title = "Methods", summary = "Reusable blocks of logic.",
            iconId = "java", estMinutes = 7,
            steps = listOf(
                LearnStepDef.Concept(
                    "java-methods-c", "Writing a method",
                    listOf(
                        text("A **method** takes parameters and can return a value:"),
                        code(
                            """
                            static int add(int a, int b) {
                                return a + b;
                            }
                            """,
                            "java",
                        ),
                        text("The `int` before the name is the **return type**. `static` lets `main` call it without creating an object."),
                    ),
                ),
                LearnStepDef.Interactive(
                    "java-methods-i", "Write add()",
                    listOf(text("Add a `static int add(int a, int b)` method, then print `add(2, 3)` (which is `5`).")),
                    starterCode = """
                        public class Main {
                            // Add your add method here

                            public static void main(String[] args) {
                                // Print the result of add(2, 3)
                            }
                        }
                    """,
                    language = "java",
                    hints = listOf(
                        "static int add(int a, int b) { return a + b; }",
                        "Then: System.out.println(add(2, 3));",
                    ),
                    solution = """
                        public class Main {
                            static int add(int a, int b) {
                                return a + b;
                            }

                            public static void main(String[] args) {
                                System.out.println(add(2, 3));
                            }
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "5", requireSource = listOf("static int add", "add(2, 3)")),
                ),
            ),
        ),
        LearnLessonDef(
            id = "java-if", title = "Making decisions", summary = "Branch with if / else.",
            iconId = "java", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "java-if-c", "if / else",
                    listOf(
                        text("`if` runs code when a condition is true; `else` handles the rest:"),
                        code(
                            """
                            int n = 42;
                            if (n > 10) {
                                System.out.println("big");
                            } else {
                                System.out.println("small");
                            }
                            """,
                            "java",
                        ),
                    ),
                ),
                LearnStepDef.Interactive(
                    "java-if-i", "Big or small?",
                    listOf(text("`n` is `42`. Print `big` when `n` is greater than `10`, otherwise `small`.")),
                    starterCode = """
                        public class Main {
                            public static void main(String[] args) {
                                int n = 42;
                                // Print "big" if n > 10, else "small"
                            }
                        }
                    """,
                    language = "java",
                    hints = listOf(
                        "if (n > 10) { ... } else { ... }",
                        "System.out.println(\"big\"); inside the first branch.",
                    ),
                    solution = """
                        public class Main {
                            public static void main(String[] args) {
                                int n = 42;
                                if (n > 10) {
                                    System.out.println("big");
                                } else {
                                    System.out.println("small");
                                }
                            }
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "big", requireSource = listOf("if (")),
                ),
            ),
        ),
        LearnLessonDef(
            id = "java-loops", title = "Repeating with loops", summary = "The classic for loop.",
            iconId = "java", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "java-loops-c", "The for loop",
                    listOf(
                        text("A `for` loop has three parts: start, condition, and step:"),
                        code(
                            """
                            for (int i = 1; i <= 5; i++) {
                                System.out.println(i);
                            }
                            """,
                            "java",
                        ),
                        text("This starts at `1`, keeps going while `i <= 5`, and adds `1` each time (`i++`)."),
                    ),
                ),
                LearnStepDef.Interactive(
                    "java-loops-i", "Count to five",
                    listOf(text("Print the numbers `1` to `5`, each on its own line.")),
                    starterCode = """
                        public class Main {
                            public static void main(String[] args) {
                                // Loop from 1 to 5 and print each number
                            }
                        }
                    """,
                    language = "java",
                    hints = listOf(
                        "for (int i = 1; i <= 5; i++) { ... }",
                        "System.out.println(i); inside the loop.",
                    ),
                    solution = """
                        public class Main {
                            public static void main(String[] args) {
                                for (int i = 1; i <= 5; i++) {
                                    System.out.println(i);
                                }
                            }
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "1\n2\n3\n4\n5", requireSource = listOf("for (")),
                ),
                LearnStepDef.Quiz(
                    "java-loops-q", "Quick check",
                    prompt = "What does i++ do each time the loop runs?",
                    options = listOf("resets i to 0", "adds 1 to i", "prints i", "ends the loop"),
                    correctIndex = 1,
                    explanation = "i++ increases i by one after each pass.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "java-basics-checkpoint", title = "Java Basics review", kind = "checkpoint",
            summary = "Five questions across the whole track. Aim to get them right first time.",
            iconId = "check", estMinutes = 4,
            steps = listOf(
                LearnStepDef.Quiz(
                    "java-cp-q1", "Question 1",
                    prompt = "Which type holds a whole number like 42?",
                    options = listOf("int", "String", "boolean", "char"),
                    correctIndex = 0,
                    explanation = "`int` is a whole number. `double` would hold a decimal, `String` text.",
                ),
                LearnStepDef.Quiz(
                    "java-cp-q2", "Question 2",
                    prompt = "What does this print?\n\nint x = 10;\nx = x + 5;\nSystem.out.println(x);",
                    options = listOf("10", "15", "x + 5", "105"),
                    correctIndex = 1,
                    explanation = "x starts at 10 and is replaced by 10 + 5.",
                ),
                LearnStepDef.Quiz(
                    "java-cp-q3", "Question 3",
                    prompt = "What does a method declared `void` return?",
                    options = listOf("Nothing", "An int", "A String", "Always null"),
                    correctIndex = 0,
                    explanation = "`void` means the method does its work and hands nothing back.",
                ),
                LearnStepDef.Quiz(
                    "java-cp-q4", "Question 4",
                    prompt = "How many times does `for (int i = 0; i < 3; i++)` run its body?",
                    options = listOf("2", "3", "4", "It never runs"),
                    correctIndex = 1,
                    explanation = "i takes 0, 1 and 2. At 3 the condition i < 3 is false and the loop stops.",
                ),
                LearnStepDef.Quiz(
                    "java-cp-q5", "Question 5",
                    prompt = "Which expression checks whether two ints a and b are equal?",
                    options = listOf("a = b", "a == b", "a === b", "a := b"),
                    correctIndex = 1,
                    explanation = "`==` compares. A single `=` assigns.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "java-basics-capstone", title = "Sum and evens", kind = "capstone",
            summary = "Read numbers with a Scanner and summarise them.",
            iconId = "java", estMinutes = 12,
            steps = listOf(
                LearnStepDef.Concept(
                    "java-cap-c", "Reading input with Scanner",
                    listOf(
                        text("`java.util.Scanner` reads input. `nextInt()` reads the next whole number, skipping spaces and line breaks:"),
                        code(
                            """
                            import java.util.Scanner;

                            Scanner in = new Scanner(System.in);
                            int count = in.nextInt();
                            for (int i = 0; i < count; i++) {
                                int value = in.nextInt();
                            }
                            """,
                            "java",
                        ),
                        tip("This exercise is checked against several different inputs, so the program has to really work it out every time."),
                    ),
                ),
                LearnStepDef.Interactive(
                    "java-cap-i", "Sum and evens",
                    listOf(
                        text("The input is a count, then that many whole numbers. Print the **sum** of the numbers and how many of them are **even**:"),
                        code(
                            """
                            Sum: 10
                            Evens: 2
                            """,
                            "plain",
                        ),
                        text("That is the output for the input `4` followed by `1 2 3 4`. Remember that 0 and negative even numbers are even too."),
                    ),
                    starterCode = """
                        import java.util.Scanner;

                        public class Main {
                            public static void main(String[] args) {
                                Scanner in = new Scanner(System.in);
                                int count = in.nextInt();
                                // Read `count` numbers, then print:
                                // Sum: <the total>
                                // Evens: <how many are even>
                            }
                        }
                    """,
                    language = "java",
                    hints = listOf(
                        "Keep two int variables, a running sum and an even counter, both starting at 0.",
                        "A number n is even when n % 2 == 0. That works for negative numbers too.",
                        "Print with System.out.println(\"Sum: \" + sum);",
                    ),
                    solution = """
                        import java.util.Scanner;

                        public class Main {
                            public static void main(String[] args) {
                                Scanner in = new Scanner(System.in);
                                int count = in.nextInt();
                                int sum = 0;
                                int evens = 0;
                                for (int i = 0; i < count; i++) {
                                    int n = in.nextInt();
                                    sum += n;
                                    if (n % 2 == 0) evens++;
                                }
                                System.out.println("Sum: " + sum);
                                System.out.println("Evens: " + evens);
                            }
                        }
                    """,
                    check = ExerciseCheck(
                        cases = listOf(
                            ExerciseCase("4\n1 2 3 4\n", "Sum: 10\nEvens: 2"),
                            ExerciseCase("3\n-2 7 0\n", "Sum: 5\nEvens: 2"),
                            ExerciseCase("1\n9\n", "Sum: 9\nEvens: 0"),
                            ExerciseCase("6\n10 20 30 41 -51 6\n", "Sum: 56\nEvens: 4"),
                        ),
                    ),
                ),
            ),
        ),
    ),
)

// ===========================================================================
// Kotlin: Next Steps (intermediate — all stdlib, fully interactive)
// ===========================================================================

private fun kotlinNextSteps() = LearnTrackDef(
    id = "kotlin-next",
    title = "Kotlin: Next Steps",
    subtitle = "Null safety, collections, data classes, and lambdas",
    iconId = "kotlin",
    accentColor = ACCENT_KOTLIN2,
    language = "kotlin",
    category = "Kotlin",
    level = "Beginner",
    prerequisites = listOf("kotlin-basics"),
    lessons = listOf(
        LearnLessonDef(
            id = "kt-null", title = "Null safety", summary = "Handle absent values without crashes.",
            iconId = "kotlin", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "kt-null-c", "Nullable types",
                    listOf(
                        text("Kotlin separates values that can be `null` from those that can't. A `?` makes a type nullable:"),
                        code(
                            """
                            val sure: String = "hi"     // never null
                            val maybe: String? = null   // might be null

                            val len = maybe?.length ?: 0 // safe-call, then a default
                            """
                        ),
                        text("`?.` calls a member only if the value isn't null; the **Elvis** operator `?:` supplies a fallback when the left side is null."),
                    ),
                ),
                LearnStepDef.Interactive(
                    "kt-null-i", "Default when null",
                    listOf(text("`name` is null. Print its length, or `0` when it's null — use `?.` and `?:`.")),
                    starterCode = """
                        fun main() {
                            val name: String? = null
                            // Print name's length, or 0 if name is null
                        }
                    """,
                    language = "kotlin",
                    hints = listOf("Safe-call the length: name?.length", "Add a fallback with Elvis: name?.length ?: 0"),
                    solution = """
                        fun main() {
                            val name: String? = null
                            println(name?.length ?: 0)
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "0", requireSource = listOf("?:")),
                ),
                LearnStepDef.Quiz(
                    "kt-null-q", "Quick check",
                    prompt = "What does the Elvis operator ?: do?",
                    options = listOf(
                        "Throws if the value is null",
                        "Provides a fallback when the left side is null",
                        "Converts a value to a String",
                        "Repeats a loop",
                    ),
                    correctIndex = 1,
                    explanation = "a ?: b evaluates to a when it isn't null, otherwise b.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "kt-collections", title = "Collections", summary = "Transform lists with filter and map.",
            iconId = "kotlin", estMinutes = 7,
            steps = listOf(
                LearnStepDef.Concept(
                    "kt-collections-c", "Lists and pipelines",
                    listOf(
                        text("`listOf(...)` builds a read-only list. You transform lists with functions like `filter`, `map`, and `sum`:"),
                        code(
                            """
                            val nums = listOf(1, 2, 3, 4)
                            val evens = nums.filter { it % 2 == 0 }  // [2, 4]
                            val doubled = nums.map { it * 2 }        // [2, 4, 6, 8]
                            """
                        ),
                        text("Inside `{ ... }`, `it` is the current element. These chain, so you can filter then sum in one line."),
                    ),
                ),
                LearnStepDef.Interactive(
                    "kt-collections-i", "Sum the even numbers",
                    listOf(text("Given `1..6`, print the sum of the **even** numbers (should be `12`). Use `filter` and `sum`.")),
                    starterCode = """
                        fun main() {
                            val nums = listOf(1, 2, 3, 4, 5, 6)
                            // Print the sum of the even numbers
                        }
                    """,
                    language = "kotlin",
                    hints = listOf("Keep the evens: nums.filter { it % 2 == 0 }", "Then add them up: .sum()"),
                    solution = """
                        fun main() {
                            val nums = listOf(1, 2, 3, 4, 5, 6)
                            println(nums.filter { it % 2 == 0 }.sum())
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "12", requireSource = listOf(".filter", ".sum")),
                ),
                LearnStepDef.Quiz(
                    "kt-collections-q", "Quick check",
                    prompt = "What does list.filter { it > 0 } return?",
                    options = listOf(
                        "The first element greater than 0",
                        "A new list of only the elements greater than 0",
                        "The number of elements greater than 0",
                        "true or false",
                    ),
                    correctIndex = 1,
                    explanation = "filter returns a new list containing only the elements the predicate keeps.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "kt-data", title = "Data classes", summary = "Model data with almost no boilerplate.",
            iconId = "kotlin", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "kt-data-c", "data class",
                    listOf(
                        text("A **data class** models a bundle of values. Kotlin generates `toString`, `equals`, `hashCode`, and `copy` for you:"),
                        code(
                            """
                            data class User(val name: String, val age: Int)

                            val u = User("Sam", 3)
                            println(u)   // User(name=Sam, age=3)
                            """
                        ),
                    ),
                ),
                LearnStepDef.Interactive(
                    "kt-data-i", "Make a User",
                    listOf(text("Define `data class User(val name: String, val age: Int)`, then create and print `User(\"Sam\", 3)`.\n\nIt should print exactly `User(name=Sam, age=3)`.")),
                    starterCode = """
                        // Define a data class User with name: String and age: Int

                        fun main() {
                            // Create a User named "Sam" aged 3 and print it
                        }
                    """,
                    language = "kotlin",
                    hints = listOf("data class User(val name: String, val age: Int)", "println(User(\"Sam\", 3))"),
                    solution = """
                        data class User(val name: String, val age: Int)

                        fun main() {
                            println(User("Sam", 3))
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "User(name=Sam, age=3)", requireSource = listOf("data class User", "println(User")),
                ),
                LearnStepDef.Quiz(
                    "kt-data-q", "Quick check",
                    prompt = "Which does a data class generate automatically?",
                    options = listOf("Only a constructor", "toString, equals, hashCode, and copy", "A main function", "Nothing"),
                    correctIndex = 1,
                    explanation = "data classes generate toString/equals/hashCode/copy from the properties in the primary constructor.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "kt-lambdas", title = "Lambdas & higher-order functions", summary = "Pass behavior as a value.",
            iconId = "kotlin", estMinutes = 7,
            steps = listOf(
                LearnStepDef.Concept(
                    "kt-lambdas-c", "Functions that take functions",
                    listOf(
                        text("A **lambda** is a block of code you can pass around: `{ x -> x * 2 }`. A **higher-order function** takes one as a parameter:"),
                        code(
                            """
                            fun applyOp(x: Int, op: (Int) -> Int): Int = op(x)

                            println(applyOp(5) { it * 2 })  // 10
                            """
                        ),
                        text("When the lambda is the last argument you can put it outside the parentheses; `it` names its single parameter."),
                    ),
                ),
                LearnStepDef.Interactive(
                    "kt-lambdas-i", "Pass a doubling lambda",
                    listOf(text("`applyOp` is provided. Call it on `5` with a lambda that doubles its input, and print the result (`10`).")),
                    starterCode = """
                        fun applyOp(x: Int, op: (Int) -> Int): Int = op(x)

                        fun main() {
                            // Print applyOp(5) with a lambda that doubles x
                        }
                    """,
                    language = "kotlin",
                    hints = listOf("Trailing lambda: applyOp(5) { it * 2 }", "Wrap it in println(...)"),
                    solution = """
                        fun applyOp(x: Int, op: (Int) -> Int): Int = op(x)

                        fun main() {
                            println(applyOp(5) { it * 2 })
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "10", requireSource = listOf("applyOp(5)", "it * 2")),
                ),
            ),
        ),
        LearnLessonDef(
            id = "kt-ext", title = "Extension functions", summary = "Add methods to existing types.",
            iconId = "kotlin", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "kt-ext-c", "Extending a type",
                    listOf(
                        text("An **extension function** adds a method to a type you don't own. Inside it, `this` is the receiver:"),
                        code(
                            """
                            fun String.shout(): String = this.uppercase() + "!"

                            println("hi".shout())  // HI!
                            """
                        ),
                    ),
                ),
                LearnStepDef.Interactive(
                    "kt-ext-i", "Double an Int",
                    listOf(text("Write an extension `fun Int.double(): Int` returning the value times two, then print `21.double()` (`42`).")),
                    starterCode = """
                        // Add an extension function Int.double() returning the value times 2

                        fun main() {
                            // Print 21.double()
                        }
                    """,
                    language = "kotlin",
                    hints = listOf("fun Int.double(): Int = this * 2", "Then call it: println(21.double())"),
                    solution = """
                        fun Int.double(): Int = this * 2

                        fun main() {
                            println(21.double())
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "42", requireSource = listOf("fun Int.double", "21.double()")),
                ),
                LearnStepDef.Quiz(
                    "kt-ext-q", "Quick check",
                    prompt = "What does an extension function let you do?",
                    options = listOf(
                        "Rename a class",
                        "Add a new method to an existing type without editing it",
                        "Make a function run faster",
                        "Delete a method",
                    ),
                    correctIndex = 1,
                    explanation = "Extensions add functions to a type (even one you can't modify); they're resolved statically.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "kt-when", title = "The when expression", summary = "Branch cleanly on a value.",
            iconId = "kotlin", estMinutes = 5,
            steps = listOf(
                LearnStepDef.Concept(
                    "kt-when-c", "when",
                    listOf(
                        text("`when` is Kotlin's powerful switch. As an **expression** it returns a value, and each branch can be a range, a set, or a condition:"),
                        code(
                            """
                            val x = 2
                            val label = when (x) {
                                1 -> "one"
                                2 -> "two"
                                else -> "other"
                            }
                            """
                        ),
                        note("Paired with a **sealed class** (a closed set of subtypes), `when` can be checked for exhaustiveness — the compiler makes sure you covered every case."),
                    ),
                ),
                LearnStepDef.Interactive(
                    "kt-when-i", "Name the number",
                    listOf(text("`x` is `2`. Use a `when` expression to print `two` for `2`, and `other` for anything else.")),
                    starterCode = """
                        fun main() {
                            val x = 2
                            // Use a when expression to print "two" when x is 2, else "other"
                        }
                    """,
                    language = "kotlin",
                    hints = listOf("val label = when (x) { 2 -> \"two\"; else -> \"other\" }", "Then println(label)"),
                    solution = """
                        fun main() {
                            val x = 2
                            val label = when (x) {
                                2 -> "two"
                                else -> "other"
                            }
                            println(label)
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "two", requireSource = listOf("when")),
                ),
            ),
        ),
    ),
)

// ===========================================================================
// Kotlin: Coroutines (advanced — interactive; the scratch resolves kotlinx-coroutines)
// ===========================================================================

private fun kotlinCoroutines() = LearnTrackDef(
    id = "kotlin-coroutines",
    title = "Kotlin: Coroutines",
    subtitle = "Asynchronous, non-blocking code the easy way",
    iconId = "kotlin",
    accentColor = ACCENT_COROUTINES,
    language = "kotlin",
    category = "Kotlin",
    level = "Advanced",
    prerequisites = listOf("kotlin-oo"),
    lessons = listOf(
        LearnLessonDef(
            id = "co-intro", title = "Suspend and delay", summary = "Run non-blocking work with delay().",
            iconId = "kotlin", estMinutes = 8,
            steps = listOf(
                LearnStepDef.Concept(
                    "co-intro-c", "What is a coroutine?",
                    listOf(
                        text("A **coroutine** is a lightweight thread you can suspend and resume without blocking a real thread. You can run thousands of them."),
                        text("`runBlocking { }` bridges normal code into the coroutine world; `delay(ms)` **suspends** the coroutine (unlike `Thread.sleep`, it doesn't block the thread):"),
                        code(
                            """
                            import kotlinx.coroutines.*

                            fun main() {
                                runBlocking {
                                    println("Start")
                                    delay(100)
                                    println("Done")
                                }
                            }
                            """
                        ),
                        tip("The first time you open a coroutine lesson, the workspace downloads the coroutines library — that's what the \"Preparing\" step is doing."),
                    ),
                ),
                LearnStepDef.Interactive(
                    "co-intro-i", "Your first coroutine",
                    listOf(text("Inside `runBlocking`, print `Start`, wait 100ms with `delay`, then print `Done`.")),
                    starterCode = """
                        import kotlinx.coroutines.*

                        fun main() {
                            runBlocking {
                                println("Start")
                                // wait 100 milliseconds (without blocking), then print Done
                            }
                        }
                    """,
                    language = "kotlin-coroutines",
                    hints = listOf("Suspend with delay(100)", "Then println(\"Done\")"),
                    solution = """
                        import kotlinx.coroutines.*

                        fun main() {
                            runBlocking {
                                println("Start")
                                delay(100)
                                println("Done")
                            }
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "Start\nDone", requireSource = listOf("runBlocking", "delay(")),
                ),
                LearnStepDef.Quiz(
                    "co-intro-q", "Quick check",
                    prompt = "How is delay() different from Thread.sleep()?",
                    options = listOf(
                        "It's exactly the same",
                        "delay() suspends the coroutine without blocking the underlying thread",
                        "delay() is slower",
                        "delay() blocks every thread",
                    ),
                    correctIndex = 1,
                    explanation = "delay() suspends only the coroutine, freeing the thread to do other work.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "co-async", title = "Concurrency with async", summary = "Run work in parallel and combine results.",
            iconId = "kotlin", estMinutes = 8,
            steps = listOf(
                LearnStepDef.Concept(
                    "co-async-c", "async / await",
                    listOf(
                        text("`async { }` starts a coroutine that computes a result, returning a `Deferred`. Call `await()` to get the value — the two `async` blocks run concurrently:"),
                        code(
                            """
                            runBlocking {
                                val a = async { compute1() }
                                val b = async { compute2() }
                                println(a.await() + b.await())
                            }
                            """
                        ),
                    ),
                ),
                LearnStepDef.Interactive(
                    "co-async-i", "Add two results",
                    listOf(text("`a` and `b` are computed with `async`. Print their sum using `await` (should be `5`).")),
                    starterCode = """
                        import kotlinx.coroutines.*

                        fun main() {
                            runBlocking {
                                val a = async { 2 }
                                val b = async { 3 }
                                // Print the sum of a and b (await each)
                            }
                        }
                    """,
                    language = "kotlin-coroutines",
                    hints = listOf("Get each value with .await()", "println(a.await() + b.await())"),
                    solution = """
                        import kotlinx.coroutines.*

                        fun main() {
                            runBlocking {
                                val a = async { 2 }
                                val b = async { 3 }
                                println(a.await() + b.await())
                            }
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "5", requireSource = listOf("async", "await")),
                ),
            ),
        ),
        LearnLessonDef(
            id = "co-launch", title = "launch: fire-and-forget", summary = "Start background work that returns nothing.",
            iconId = "kotlin", estMinutes = 7,
            steps = listOf(
                LearnStepDef.Concept(
                    "co-launch-c", "launch",
                    listOf(
                        text("`launch { }` starts a coroutine that doesn't return a result — great for background work. `runBlocking` waits for its children to finish before returning."),
                        code(
                            """
                            runBlocking {
                                launch {
                                    delay(50)
                                    println("World")
                                }
                                print("Hello, ")
                            }
                            """
                        ),
                        text("The launched coroutine is queued, so `print(\"Hello, \")` runs first; then the coroutine resumes after its delay."),
                    ),
                ),
                LearnStepDef.Interactive(
                    "co-launch-i", "Hello, World in order",
                    listOf(text("Inside the `launch` (already started), it prints `World` after a delay. Before it, `print` `Hello, ` (no newline) so the output reads `Hello, World`.")),
                    starterCode = """
                        import kotlinx.coroutines.*

                        fun main() {
                            runBlocking {
                                launch {
                                    delay(50)
                                    println("World")
                                }
                                // print "Hello, " here (no newline)
                            }
                        }
                    """,
                    language = "kotlin-coroutines",
                    hints = listOf("Use print (not println) so there's no line break", "print(\"Hello, \")"),
                    solution = """
                        import kotlinx.coroutines.*

                        fun main() {
                            runBlocking {
                                launch {
                                    delay(50)
                                    println("World")
                                }
                                print("Hello, ")
                            }
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "Hello, World", requireSource = listOf("launch", "print(")),
                ),
            ),
        ),
        LearnLessonDef(
            id = "co-flow", title = "Flows", summary = "A stream of values over time.",
            iconId = "kotlin", estMinutes = 7,
            steps = listOf(
                LearnStepDef.Concept(
                    "co-flow-c", "Flow",
                    listOf(
                        text("A **Flow** emits a sequence of values over time; you `collect` them. Think of it as a suspendable, asynchronous list:"),
                        code(
                            """
                            import kotlinx.coroutines.*
                            import kotlinx.coroutines.flow.*

                            fun main() {
                                runBlocking {
                                    flowOf(1, 2, 3).collect { println(it) }
                                }
                            }
                            """
                        ),
                    ),
                ),
                LearnStepDef.Interactive(
                    "co-flow-i", "Collect a flow",
                    listOf(text("Emit `1, 2, 3` from a flow and print each value, one per line.")),
                    starterCode = """
                        import kotlinx.coroutines.*
                        import kotlinx.coroutines.flow.*

                        fun main() {
                            runBlocking {
                                // Build a flow of 1, 2, 3 and collect it, printing each value
                            }
                        }
                    """,
                    language = "kotlin-coroutines",
                    hints = listOf("flowOf(1, 2, 3) builds the flow", "Collect it: .collect { println(it) }"),
                    solution = """
                        import kotlinx.coroutines.*
                        import kotlinx.coroutines.flow.*

                        fun main() {
                            runBlocking {
                                flowOf(1, 2, 3).collect { println(it) }
                            }
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "1\n2\n3", requireSource = listOf("flowOf", "collect")),
                ),
                LearnStepDef.Quiz(
                    "co-flow-q", "Quick check",
                    prompt = "What does a Flow represent?",
                    options = listOf("A single value", "A stream of values produced over time", "A thread", "A file"),
                    correctIndex = 1,
                    explanation = "A Flow emits multiple values asynchronously; you receive them by collecting.",
                ),
            ),
        ),
    ),
)

// ===========================================================================
// Kotlin: Object-Oriented (stdlib — classes, inheritance, interfaces)
// ===========================================================================

private fun kotlinOo() = LearnTrackDef(
    id = "kotlin-oo",
    title = "Kotlin: Object-Oriented",
    subtitle = "Classes, inheritance, interfaces, and enums",
    iconId = "kotlin",
    accentColor = ACCENT_KOTLIN_OO,
    language = "kotlin",
    category = "Kotlin",
    level = "Intermediate",
    prerequisites = listOf("kotlin-next"),
    lessons = listOf(
        LearnLessonDef(
            id = "kt-oo-class", title = "Classes & constructors", summary = "Bundle data with behavior.",
            iconId = "kotlin", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "kt-oo-class-c", "A class with a method",
                    listOf(
                        text("A **primary constructor** declares properties right in the header. Methods use those properties:"),
                        code(
                            """
                            class Point(val x: Int, val y: Int) {
                                fun sum(): Int = x + y
                            }

                            println(Point(2, 3).sum())  // 5
                            """
                        ),
                    ),
                ),
                LearnStepDef.Interactive(
                    "kt-oo-class-i", "Give Point a sum()",
                    listOf(text("Add a `sum()` method to `Point` returning `x + y`, then print `Point(2, 3).sum()` (`5`).")),
                    starterCode = """
                        class Point(val x: Int, val y: Int) {
                            // add a sum() method returning x + y
                        }

                        fun main() {
                            // Print Point(2, 3).sum()
                        }
                    """,
                    language = "kotlin",
                    hints = listOf("fun sum(): Int = x + y", "println(Point(2, 3).sum())"),
                    solution = """
                        class Point(val x: Int, val y: Int) {
                            fun sum(): Int = x + y
                        }

                        fun main() {
                            println(Point(2, 3).sum())
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "5", requireSource = listOf("class Point", "Point(2, 3)", "sum")),
                ),
            ),
        ),
        LearnLessonDef(
            id = "kt-oo-inherit", title = "Inheritance", summary = "Override behavior in a subclass.",
            iconId = "kotlin", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "kt-oo-inherit-c", "open & override",
                    listOf(
                        text("Kotlin classes are final by default. Mark a class and its members `open` to allow subclassing, then `override` them:"),
                        code(
                            """
                            open class Animal {
                                open fun sound(): String = "..."
                            }

                            class Cat : Animal() {
                                override fun sound(): String = "Meow"
                            }
                            """
                        ),
                    ),
                ),
                LearnStepDef.Interactive(
                    "kt-oo-inherit-i", "Make a Cat",
                    listOf(text("Create `class Cat : Animal()` that overrides `sound()` to return `\"Meow\"`, then print `Cat().sound()`.")),
                    starterCode = """
                        open class Animal {
                            open fun sound(): String = "..."
                        }

                        // Make a Cat that overrides sound() to return "Meow"

                        fun main() {
                            // Print Cat().sound()
                        }
                    """,
                    language = "kotlin",
                    hints = listOf("class Cat : Animal() { override fun sound(): String = \"Meow\" }", "println(Cat().sound())"),
                    solution = """
                        open class Animal {
                            open fun sound(): String = "..."
                        }

                        class Cat : Animal() {
                            override fun sound(): String = "Meow"
                        }

                        fun main() {
                            println(Cat().sound())
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "Meow", requireSource = listOf(": Animal()", "override", "Cat()")),
                ),
                LearnStepDef.Quiz(
                    "kt-oo-inherit-q", "Quick check",
                    prompt = "Why does a Kotlin class need the open keyword?",
                    options = listOf(
                        "To make it public",
                        "Classes are final by default; open allows subclassing",
                        "To add a constructor",
                        "To import it",
                    ),
                    correctIndex = 1,
                    explanation = "Kotlin classes/members are final unless marked open (or abstract).",
                ),
            ),
        ),
        LearnLessonDef(
            id = "kt-oo-interface", title = "Interfaces", summary = "Define a contract to implement.",
            iconId = "kotlin", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "kt-oo-interface-c", "interface",
                    listOf(
                        text("An **interface** is a contract of methods a class promises to provide:"),
                        code(
                            """
                            interface Greeter {
                                fun greet(): String
                            }

                            class English : Greeter {
                                override fun greet(): String = "Hello"
                            }
                            """
                        ),
                    ),
                ),
                LearnStepDef.Interactive(
                    "kt-oo-interface-i", "Implement Greeter",
                    listOf(text("Make `class English : Greeter` whose `greet()` returns `\"Hello\"`, then print `English().greet()`.")),
                    starterCode = """
                        interface Greeter {
                            fun greet(): String
                        }

                        // Implement Greeter in a class English whose greet() returns "Hello"

                        fun main() {
                            // Print English().greet()
                        }
                    """,
                    language = "kotlin",
                    hints = listOf("class English : Greeter { override fun greet(): String = \"Hello\" }", "println(English().greet())"),
                    solution = """
                        interface Greeter {
                            fun greet(): String
                        }

                        class English : Greeter {
                            override fun greet(): String = "Hello"
                        }

                        fun main() {
                            println(English().greet())
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "Hello", requireSource = listOf("interface Greeter", ": Greeter", "override")),
                ),
            ),
        ),
        LearnLessonDef(
            id = "kt-oo-enum", title = "Enum classes", summary = "A fixed set of named values.",
            iconId = "kotlin", estMinutes = 5,
            steps = listOf(
                LearnStepDef.Concept(
                    "kt-oo-enum-c", "enum class",
                    listOf(
                        text("An **enum** defines a fixed set of constants. Each has a `name` and prints as that name:"),
                        code(
                            """
                            enum class Color { RED, GREEN, BLUE }

                            println(Color.GREEN)  // GREEN
                            """
                        ),
                    ),
                ),
                LearnStepDef.Interactive(
                    "kt-oo-enum-i", "Print a color",
                    listOf(text("Define `enum class Color { RED, GREEN, BLUE }` and print `Color.GREEN` (prints `GREEN`).")),
                    starterCode = """
                        // Define an enum class Color with RED, GREEN, BLUE

                        fun main() {
                            // Print Color.GREEN
                        }
                    """,
                    language = "kotlin",
                    hints = listOf("enum class Color { RED, GREEN, BLUE }", "println(Color.GREEN)"),
                    solution = """
                        enum class Color { RED, GREEN, BLUE }

                        fun main() {
                            println(Color.GREEN)
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "GREEN", requireSource = listOf("enum class Color", "Color.GREEN")),
                ),
            ),
        ),
        LearnLessonDef(
            id = "kt-oo-object", title = "Objects & companions", summary = "Singletons and factory methods.",
            iconId = "kotlin", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "kt-oo-object-c", "object & companion object",
                    listOf(
                        text("`object` declares a **singleton**. A **companion object** holds members you call on the class itself (like a static factory):"),
                        code(
                            """
                            class Box {
                                companion object {
                                    fun create(): String = "box"
                                }
                            }

                            println(Box.create())  // box
                            """
                        ),
                    ),
                ),
                LearnStepDef.Interactive(
                    "kt-oo-object-i", "A factory method",
                    listOf(text("Give `Box` a `companion object` with `create()` returning `\"box\"`, then print `Box.create()`.")),
                    starterCode = """
                        class Box {
                            // add a companion object with create() returning "box"
                        }

                        fun main() {
                            // Print Box.create()
                        }
                    """,
                    language = "kotlin",
                    hints = listOf("companion object { fun create(): String = \"box\" }", "println(Box.create())"),
                    solution = """
                        class Box {
                            companion object {
                                fun create(): String = "box"
                            }
                        }

                        fun main() {
                            println(Box.create())
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "box", requireSource = listOf("companion object", "Box.create()")),
                ),
            ),
        ),
    ),
)

// ===========================================================================
// Kotlin: Practice (stdlib — small coding challenges)
// ===========================================================================

private fun kotlinPractice() = LearnTrackDef(
    id = "kotlin-practice",
    title = "Kotlin: Practice",
    subtitle = "Small coding challenges to sharpen your skills",
    iconId = "sparkle",
    accentColor = ACCENT_PRACTICE,
    language = "kotlin",
    category = "Kotlin",
    level = "Intermediate",
    prerequisites = listOf("kotlin-next"),
    lessons = listOf(
        LearnLessonDef(
            id = "pr-fizzbuzz", title = "FizzBuzz", summary = "The classic warm-up.",
            iconId = "sparkle", estMinutes = 7,
            steps = listOf(
                LearnStepDef.Concept(
                    "pr-fizzbuzz-c", "The rules",
                    listOf(
                        text("For each number `1..5`, print `Fizz` if it's divisible by 3, `Buzz` if divisible by 5, otherwise the number itself. Combine a `for` loop with `when`."),
                        text("Divisibility is the remainder operator: `i % 3 == 0` means \"i is a multiple of 3\"."),
                    ),
                ),
                LearnStepDef.Interactive(
                    "pr-fizzbuzz-i", "Play FizzBuzz to 5",
                    listOf(text("Print `1`, `2`, `Fizz`, `4`, `Buzz` — one per line — by looping `1..5` and testing divisibility by 3 and 5.")),
                    starterCode = """
                        fun main() {
                            for (i in 1..5) {
                                // print Fizz for multiples of 3, Buzz for 5, else the number
                            }
                        }
                    """,
                    language = "kotlin",
                    hints = listOf("Use when { i % 3 == 0 -> ...; i % 5 == 0 -> ...; else -> ... }", "println(\"Fizz\") / println(\"Buzz\") / println(i)"),
                    solution = """
                        fun main() {
                            for (i in 1..5) {
                                when {
                                    i % 3 == 0 -> println("Fizz")
                                    i % 5 == 0 -> println("Buzz")
                                    else -> println(i)
                                }
                            }
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "1\n2\nFizz\n4\nBuzz", requireSource = listOf("for (", "% 3", "% 5")),
                ),
            ),
        ),
        LearnLessonDef(
            id = "pr-reverse", title = "Reverse a string", summary = "Flip it around.",
            iconId = "sparkle", estMinutes = 4,
            steps = listOf(
                LearnStepDef.Concept(
                    "pr-reverse-c", "reversed()",
                    listOf(text("Kotlin's standard library has a `reversed()` for strings — `\"abc\".reversed()` is `\"cba\"`.")),
                ),
                LearnStepDef.Interactive(
                    "pr-reverse-i", "Reverse \"hello\"",
                    listOf(text("Print `\"hello\"` reversed (should be `olleh`).")),
                    starterCode = """
                        fun main() {
                            val word = "hello"
                            // Print word reversed
                        }
                    """,
                    language = "kotlin",
                    hints = listOf("word.reversed() gives the reversed string"),
                    solution = """
                        fun main() {
                            val word = "hello"
                            println(word.reversed())
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "olleh", requireSource = listOf(".reversed")),
                ),
            ),
        ),
        LearnLessonDef(
            id = "pr-factorial", title = "Factorial", summary = "Multiply your way up.",
            iconId = "sparkle", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "pr-factorial-c", "n!",
                    listOf(text("The factorial of `n` is `1 * 2 * ... * n`. So `5! = 120`. A loop that multiplies a running total does it.")),
                ),
                LearnStepDef.Interactive(
                    "pr-factorial-i", "Compute 5!",
                    listOf(text("Write a `factorial(n)` function and print `factorial(5)` (`120`).")),
                    starterCode = """
                        // Write a factorial(n: Int): Int function

                        fun main() {
                            // Print factorial(5)
                        }
                    """,
                    language = "kotlin",
                    hints = listOf("var result = 1; for (i in 1..n) result *= i; return result", "println(factorial(5))"),
                    solution = """
                        fun factorial(n: Int): Int {
                            var result = 1
                            for (i in 1..n) result *= i
                            return result
                        }

                        fun main() {
                            println(factorial(5))
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "120", requireSource = listOf("fun factorial", "factorial(5)")),
                ),
            ),
        ),
        LearnLessonDef(
            id = "pr-vowels", title = "Count vowels", summary = "Filter and count characters.",
            iconId = "sparkle", estMinutes = 5,
            steps = listOf(
                LearnStepDef.Concept(
                    "pr-vowels-c", "count { }",
                    listOf(text("`count { }` returns how many elements match a condition. A `Char` is in a string if that string contains it: `it in \"aeiou\"`.")),
                ),
                LearnStepDef.Interactive(
                    "pr-vowels-i", "Vowels in \"education\"",
                    listOf(text("Print how many vowels are in `\"education\"` (should be `5`). Use `count { }`.")),
                    starterCode = """
                        fun main() {
                            val text = "education"
                            // Print how many vowels (a, e, i, o, u) text contains
                        }
                    """,
                    language = "kotlin",
                    hints = listOf("text.count { ... }", "A char is a vowel when it in \"aeiou\""),
                    solution = """
                        fun main() {
                            val text = "education"
                            println(text.count { it in "aeiou" })
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "5", requireSource = listOf(".count")),
                ),
            ),
        ),
        LearnLessonDef(
            id = "pr-fib", title = "Fibonacci", summary = "Each number is the sum of the previous two.",
            iconId = "sparkle", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "pr-fib-c", "The sequence",
                    listOf(text("Fibonacci: `0, 1, 1, 2, 3, 5, 8, ...` — each value is the sum of the two before it. Keep two running values and slide them forward.")),
                ),
                LearnStepDef.Interactive(
                    "pr-fib-i", "The 6th step",
                    listOf(text("Starting from `0, 1`, advance the sequence 6 times and print the result (`8`). Keep two variables and update them with `a + b`.")),
                    starterCode = """
                        fun main() {
                            var a = 0
                            var b = 1
                            // Advance 6 times: next = a + b, then a = b, b = next
                            // Then print a
                        }
                    """,
                    language = "kotlin",
                    hints = listOf("repeat(6) { val next = a + b; a = b; b = next }", "Then println(a)"),
                    solution = """
                        fun main() {
                            var a = 0
                            var b = 1
                            repeat(6) {
                                val next = a + b
                                a = b
                                b = next
                            }
                            println(a)
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "8", requireSource = listOf("repeat", "a + b")),
                ),
            ),
        ),
    ),
)

// ===========================================================================
// Compose: Introduction (live @Preview rendering via the Compose interpreter)
// ===========================================================================

private fun composeIntro() = LearnTrackDef(
    id = "compose-intro",
    title = "Compose: Introduction",
    subtitle = "Build UI with composable functions, rendered live",
    iconId = "layers",
    accentColor = ACCENT_COMPOSE,
    language = "kotlin-compose",
    category = "Compose",
    level = "Intermediate",
    prerequisites = listOf("kotlin-next"),
    lessons = listOf(
        LearnLessonDef(
            id = "ci-first", title = "Your first composable", summary = "Describe UI with @Composable, see it render.",
            iconId = "layers", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "ci-first-c", "@Composable and @Preview",
                    listOf(
                        text("Jetpack Compose builds UI from **composable functions** — regular Kotlin functions marked `@Composable` that *describe* what to show. `Text(...)` is a built-in composable that draws a line of text."),
                        text("A function also marked `@Preview` can be rendered on its own — no app launch needed. That's exactly what you see below, rendered live:"),
                        composePreview(
                            """
                            import androidx.compose.material3.Text
                            import androidx.compose.runtime.Composable
                            import androidx.compose.ui.tooling.preview.Preview

                            @Composable
                            fun Greeting() {
                                Text("Hello, Compose!")
                            }

                            @Preview
                            @Composable
                            fun GreetingPreview() {
                                Greeting()
                            }
                            """,
                            caption = "A @Composable rendered by @Preview",
                        ),
                        tip("A `@Composable` function emits UI instead of returning a value — you call other composables inside it."),
                    ),
                ),
                LearnStepDef.Concept(
                    "ci-first-play", "Try it yourself",
                    listOf(
                        text("**Edit the code** and watch the preview update. Try changing the text, or add a second `Text(...)` line inside `Greeting`."),
                        composePreview(
                            """
                            import androidx.compose.material3.Text
                            import androidx.compose.runtime.Composable
                            import androidx.compose.ui.tooling.preview.Preview

                            @Composable
                            fun Greeting() {
                                Text("Change me!")
                            }

                            @Preview
                            @Composable
                            fun GreetingPreview() {
                                Greeting()
                            }
                            """,
                            interactive = true,
                        ),
                    ),
                ),
                LearnStepDef.Quiz(
                    "ci-first-q", "Quick check",
                    prompt = "What does the @Composable annotation mark?",
                    options = listOf(
                        "A function that returns a String",
                        "A function that describes (emits) UI",
                        "The app's entry point",
                        "A background thread",
                    ),
                    correctIndex = 1,
                    explanation = "A @Composable function emits UI by calling other composables; it doesn't return a value.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "ci-layout", title = "Column, Row & Box", summary = "Arrange composables in space.",
            iconId = "layers", estMinutes = 7,
            steps = listOf(
                LearnStepDef.Concept(
                    "ci-layout-c", "Stacking and aligning",
                    listOf(
                        text("Layout composables arrange their children. A **Column** stacks them vertically, a **Row** places them side by side, and a **Box** overlaps them."),
                        composePreview(
                            """
                            import androidx.compose.foundation.layout.Column
                            import androidx.compose.foundation.layout.padding
                            import androidx.compose.material3.Text
                            import androidx.compose.runtime.Composable
                            import androidx.compose.ui.Modifier
                            import androidx.compose.ui.tooling.preview.Preview
                            import androidx.compose.ui.unit.dp

                            @Composable
                            fun Stacked() {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("First")
                                    Text("Second")
                                    Text("Third")
                                }
                            }

                            @Preview
                            @Composable
                            fun StackedPreview() {
                                Stacked()
                            }
                            """,
                            caption = "A Column stacks its children vertically",
                        ),
                    ),
                ),
                LearnStepDef.Concept(
                    "ci-layout-play", "Rows sit side by side",
                    listOf(
                        text("Swap the `Column` for a `Row` and the items line up horizontally. **Edit the code** — try switching between `Column` and `Row`, or add another `Text`."),
                        composePreview(
                            """
                            import androidx.compose.foundation.layout.Row
                            import androidx.compose.foundation.layout.padding
                            import androidx.compose.material3.Text
                            import androidx.compose.runtime.Composable
                            import androidx.compose.ui.Modifier
                            import androidx.compose.ui.tooling.preview.Preview
                            import androidx.compose.ui.unit.dp

                            @Composable
                            fun SideBySide() {
                                Row(modifier = Modifier.padding(16.dp)) {
                                    Text("Left  ")
                                    Text("Right")
                                }
                            }

                            @Preview
                            @Composable
                            fun SideBySidePreview() {
                                SideBySide()
                            }
                            """,
                            interactive = true,
                        ),
                    ),
                ),
                LearnStepDef.Quiz(
                    "ci-layout-q", "Quick check",
                    prompt = "Which layout places its children side by side, horizontally?",
                    options = listOf("Column", "Row", "Box", "Text"),
                    correctIndex = 1,
                    explanation = "Row lays children out horizontally; Column stacks them vertically; Box overlaps them.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "ci-modifiers", title = "Modifiers", summary = "Size, pad, and decorate composables.",
            iconId = "layers", estMinutes = 7,
            steps = listOf(
                LearnStepDef.Concept(
                    "ci-modifiers-c", "The Modifier chain",
                    listOf(
                        text("A **Modifier** adjusts how a composable looks and is laid out — padding, size, background, and more. You chain calls, and **order matters**: `padding` then `background` differs from `background` then `padding`."),
                        composePreview(
                            """
                            import androidx.compose.foundation.background
                            import androidx.compose.foundation.layout.fillMaxWidth
                            import androidx.compose.foundation.layout.padding
                            import androidx.compose.material3.Text
                            import androidx.compose.runtime.Composable
                            import androidx.compose.ui.Modifier
                            import androidx.compose.ui.graphics.Color
                            import androidx.compose.ui.tooling.preview.Preview
                            import androidx.compose.ui.unit.dp

                            @Composable
                            fun Banner() {
                                Text(
                                    "Styled with modifiers",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFFEDE7F6))
                                        .padding(20.dp),
                                )
                            }

                            @Preview
                            @Composable
                            fun BannerPreview() {
                                Banner()
                            }
                            """,
                            caption = "fillMaxWidth + background + padding",
                        ),
                    ),
                ),
                LearnStepDef.Concept(
                    "ci-modifiers-play", "Experiment",
                    listOf(
                        text("**Edit the modifiers** — change the padding value, swap the `background` colour, or reorder `.background(...)` and `.padding(...)` to see how it changes."),
                        composePreview(
                            """
                            import androidx.compose.foundation.background
                            import androidx.compose.foundation.layout.padding
                            import androidx.compose.material3.Text
                            import androidx.compose.runtime.Composable
                            import androidx.compose.ui.Modifier
                            import androidx.compose.ui.graphics.Color
                            import androidx.compose.ui.tooling.preview.Preview
                            import androidx.compose.ui.unit.dp

                            @Composable
                            fun Tag() {
                                Text(
                                    "Tap edit and tweak me",
                                    modifier = Modifier
                                        .background(Color(0xFFD7F0E3))
                                        .padding(16.dp),
                                )
                            }

                            @Preview
                            @Composable
                            fun TagPreview() {
                                Tag()
                            }
                            """,
                            interactive = true,
                        ),
                    ),
                ),
                LearnStepDef.Quiz(
                    "ci-modifiers-q", "Quick check",
                    prompt = "Why does the order of modifiers in the chain matter?",
                    options = listOf(
                        "It doesn't — order is ignored",
                        "Each modifier wraps the result of the previous one, so padding-then-background differs from background-then-padding",
                        "Only the first modifier is applied",
                        "Modifiers must be alphabetical",
                    ),
                    correctIndex = 1,
                    explanation = "Modifiers apply outside-in in the order written; each wraps the previous, so ordering changes the result.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "ci-state", title = "State & remember", summary = "Make the UI react to changes.",
            iconId = "layers", estMinutes = 8,
            steps = listOf(
                LearnStepDef.Concept(
                    "ci-state-c", "State drives recomposition",
                    listOf(
                        text("When **state** changes, Compose re-runs the composables that read it — this is **recomposition**. `remember { mutableStateOf(...) }` holds a value across recompositions; `by` lets you read and write it like a normal variable."),
                        text("Tap the button in the live preview — the count updates because the `Text` reads the state:"),
                        composePreview(
                            """
                            import androidx.compose.foundation.layout.Column
                            import androidx.compose.foundation.layout.padding
                            import androidx.compose.material3.Button
                            import androidx.compose.material3.Text
                            import androidx.compose.runtime.Composable
                            import androidx.compose.runtime.getValue
                            import androidx.compose.runtime.mutableStateOf
                            import androidx.compose.runtime.remember
                            import androidx.compose.runtime.setValue
                            import androidx.compose.ui.Modifier
                            import androidx.compose.ui.tooling.preview.Preview
                            import androidx.compose.ui.unit.dp

                            @Composable
                            fun Counter() {
                                var count by remember { mutableStateOf(0) }
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Count: " + count)
                                    Button(onClick = { count++ }) {
                                        Text("Increment")
                                    }
                                }
                            }

                            @Preview
                            @Composable
                            fun CounterPreview() {
                                Counter()
                            }
                            """,
                            interactive = true,
                            caption = "Tap Increment — the count is state",
                        ),
                        note("Without `remember`, the value would reset to its initial value on every recomposition."),
                    ),
                ),
                LearnStepDef.Quiz(
                    "ci-state-q", "Quick check",
                    prompt = "What does remember { mutableStateOf(0) } do?",
                    options = listOf(
                        "Runs the code on a background thread",
                        "Holds a value that survives recomposition and triggers UI updates when it changes",
                        "Creates a constant that never changes",
                        "Logs the value to the console",
                    ),
                    correctIndex = 1,
                    explanation = "mutableStateOf makes an observable value; remember keeps it across recompositions so reads recompose when it changes.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "ci-input", title = "Buttons & text input", summary = "Handle taps and typing.",
            iconId = "layers", estMinutes = 8,
            steps = listOf(
                LearnStepDef.Concept(
                    "ci-input-c", "Reacting to the user",
                    listOf(
                        text("A **Button** takes an `onClick` lambda; an **OutlinedTextField** shows an input whose `value` you keep in state and update in `onValueChange`. Together they make an interactive form."),
                        composePreview(
                            """
                            import androidx.compose.foundation.layout.Column
                            import androidx.compose.foundation.layout.padding
                            import androidx.compose.material3.OutlinedTextField
                            import androidx.compose.material3.Text
                            import androidx.compose.runtime.Composable
                            import androidx.compose.runtime.getValue
                            import androidx.compose.runtime.mutableStateOf
                            import androidx.compose.runtime.remember
                            import androidx.compose.runtime.setValue
                            import androidx.compose.ui.Modifier
                            import androidx.compose.ui.tooling.preview.Preview
                            import androidx.compose.ui.unit.dp

                            @Composable
                            fun NameField() {
                                var name by remember { mutableStateOf("") }
                                Column(modifier = Modifier.padding(16.dp)) {
                                    OutlinedTextField(
                                        value = name,
                                        onValueChange = { name = it },
                                        label = { Text("Your name") },
                                    )
                                    Text("Hello, " + name)
                                }
                            }

                            @Preview
                            @Composable
                            fun NameFieldPreview() {
                                NameField()
                            }
                            """,
                            interactive = true,
                            caption = "Type in the field — the greeting follows",
                        ),
                    ),
                ),
                LearnStepDef.Quiz(
                    "ci-input-q", "Quick check",
                    prompt = "How does an OutlinedTextField report what the user typed?",
                    options = listOf(
                        "It returns the text from the function",
                        "Through its onValueChange lambda, which you use to update state",
                        "It writes directly to a file",
                        "You read it from a global variable",
                    ),
                    correctIndex = 1,
                    explanation = "The field is stateless: you pass its value in and receive edits via onValueChange, updating your state.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "ci-lists", title = "Showing a list", summary = "Render many items from data.",
            iconId = "layers", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "ci-lists-c", "Looping in a composable",
                    listOf(
                        text("Because composables are just Kotlin, you can **loop** to emit one child per data item. A `for` loop inside a `Column` renders a row per element:"),
                        composePreview(
                            """
                            import androidx.compose.foundation.layout.Column
                            import androidx.compose.foundation.layout.padding
                            import androidx.compose.material3.Text
                            import androidx.compose.runtime.Composable
                            import androidx.compose.ui.Modifier
                            import androidx.compose.ui.tooling.preview.Preview
                            import androidx.compose.ui.unit.dp

                            @Composable
                            fun Fruits() {
                                val fruits = listOf("Apple", "Banana", "Cherry")
                                Column(modifier = Modifier.padding(16.dp)) {
                                    for (fruit in fruits) {
                                        Text(fruit, modifier = Modifier.padding(4.dp))
                                    }
                                }
                            }

                            @Preview
                            @Composable
                            fun FruitsPreview() {
                                Fruits()
                            }
                            """,
                            interactive = true,
                            caption = "One Text per list item",
                        ),
                        tip("For long, scrolling lists you'll reach for `LazyColumn` — that's in the Advanced track."),
                    ),
                ),
                LearnStepDef.Quiz(
                    "ci-lists-q", "Quick check",
                    prompt = "How do you render one composable per item in a small list?",
                    options = listOf(
                        "You can't — Compose has no loops",
                        "Loop with a for-loop inside a layout, emitting a child each pass",
                        "Call the composable once and it repeats automatically",
                        "Use a while(true) loop",
                    ),
                    correctIndex = 1,
                    explanation = "Composables are Kotlin, so a for-loop inside a Column emits one child per element.",
                ),
            ),
        ),
    ),
)

// ===========================================================================
// Compose: Advanced (state hoisting, lazy lists, theming, effects, animation)
// ===========================================================================

private fun composeAdvanced() = LearnTrackDef(
    id = "compose-advanced",
    title = "Compose: Advanced",
    subtitle = "State hoisting, lazy lists, theming, and animation",
    iconId = "layers",
    accentColor = ACCENT_COMPOSE2,
    language = "kotlin-compose",
    category = "Compose",
    level = "Advanced",
    prerequisites = listOf("compose-intro"),
    lessons = listOf(
        LearnLessonDef(
            id = "ca-hoist", title = "State hoisting", summary = "Make composables reusable and testable.",
            iconId = "layers", estMinutes = 8,
            steps = listOf(
                LearnStepDef.Concept(
                    "ca-hoist-c", "Lift state up",
                    listOf(
                        text("A **stateless** composable takes its data as parameters and reports events with callbacks; a **stateful** caller owns the state. This is **state hoisting** — it makes the display reusable and easy to preview."),
                        text("`CounterDisplay` holds no state; `CounterScreen` owns it and passes it down. Tap the button:"),
                        composePreview(
                            """
                            import androidx.compose.foundation.layout.Column
                            import androidx.compose.foundation.layout.padding
                            import androidx.compose.material3.Button
                            import androidx.compose.material3.Text
                            import androidx.compose.runtime.Composable
                            import androidx.compose.runtime.getValue
                            import androidx.compose.runtime.mutableStateOf
                            import androidx.compose.runtime.remember
                            import androidx.compose.runtime.setValue
                            import androidx.compose.ui.Modifier
                            import androidx.compose.ui.tooling.preview.Preview
                            import androidx.compose.ui.unit.dp

                            @Composable
                            fun CounterDisplay(count: Int, onIncrement: () -> Unit) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Count: " + count)
                                    Button(onClick = onIncrement) {
                                        Text("Add one")
                                    }
                                }
                            }

                            @Composable
                            fun CounterScreen() {
                                var count by remember { mutableStateOf(0) }
                                CounterDisplay(count = count, onIncrement = { count++ })
                            }

                            @Preview
                            @Composable
                            fun CounterScreenPreview() {
                                CounterScreen()
                            }
                            """,
                            interactive = true,
                            caption = "Stateless display + stateful caller",
                        ),
                    ),
                ),
                LearnStepDef.Quiz(
                    "ca-hoist-q", "Quick check",
                    prompt = "What does 'hoisting state' mean?",
                    options = listOf(
                        "Storing state in a global variable",
                        "Moving state up to a caller and passing values down + events up, so the composable is stateless",
                        "Deleting all state",
                        "Running state on another thread",
                    ),
                    correctIndex = 1,
                    explanation = "A hoisted composable receives its value as a parameter and reports changes via a callback — reusable and previewable.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "ca-lazy", title = "LazyColumn", summary = "Efficient, scrolling lists.",
            iconId = "layers", estMinutes = 7,
            steps = listOf(
                LearnStepDef.Concept(
                    "ca-lazy-c", "Only compose what's visible",
                    listOf(
                        text("A plain `Column` composes every child at once. A **LazyColumn** composes only the items on screen and reuses them as you scroll — the right tool for long lists. You describe items inside its `items(...)` block:"),
                        composePreview(
                            """
                            import androidx.compose.foundation.layout.padding
                            import androidx.compose.foundation.lazy.LazyColumn
                            import androidx.compose.foundation.lazy.items
                            import androidx.compose.material3.Text
                            import androidx.compose.runtime.Composable
                            import androidx.compose.ui.Modifier
                            import androidx.compose.ui.tooling.preview.Preview
                            import androidx.compose.ui.unit.dp

                            @Composable
                            fun ItemList() {
                                val rows = listOf("One", "Two", "Three", "Four", "Five")
                                LazyColumn(modifier = Modifier.padding(16.dp)) {
                                    items(rows) { row ->
                                        Text(row, modifier = Modifier.padding(8.dp))
                                    }
                                }
                            }

                            @Preview
                            @Composable
                            fun ItemListPreview() {
                                ItemList()
                            }
                            """,
                            caption = "LazyColumn renders items on demand",
                        ),
                        note("Use `items(list) { }` for a list of data, or `items(count) { index -> }` for a count."),
                    ),
                ),
                LearnStepDef.Quiz(
                    "ca-lazy-q", "Quick check",
                    prompt = "Why prefer LazyColumn over Column for a long list?",
                    options = listOf(
                        "It looks different",
                        "It only composes the items currently visible, so it scales to large lists",
                        "It sorts the list automatically",
                        "It runs on a background thread",
                    ),
                    correctIndex = 1,
                    explanation = "LazyColumn composes and recycles only visible items instead of all of them up front.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "ca-theme", title = "Material 3 theming", summary = "Colors and type from the theme.",
            iconId = "layers", estMinutes = 7,
            steps = listOf(
                LearnStepDef.Concept(
                    "ca-theme-c", "MaterialTheme, Card & typography",
                    listOf(
                        text("Material 3 composables read colours and text styles from **MaterialTheme**, so your UI stays consistent. A **Card** groups content on a raised surface; `MaterialTheme.typography` provides named text styles:"),
                        composePreview(
                            """
                            import androidx.compose.foundation.layout.Column
                            import androidx.compose.foundation.layout.fillMaxWidth
                            import androidx.compose.foundation.layout.padding
                            import androidx.compose.material3.Card
                            import androidx.compose.material3.MaterialTheme
                            import androidx.compose.material3.Text
                            import androidx.compose.runtime.Composable
                            import androidx.compose.ui.Modifier
                            import androidx.compose.ui.tooling.preview.Preview
                            import androidx.compose.ui.unit.dp

                            @Composable
                            fun ProfileCard() {
                                Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text("Ada Lovelace", style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "Styled by the Material theme",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                            }

                            @Preview
                            @Composable
                            fun ProfileCardPreview() {
                                ProfileCard()
                            }
                            """,
                            interactive = true,
                            caption = "A Card using theme typography",
                        ),
                    ),
                ),
                LearnStepDef.Quiz(
                    "ca-theme-q", "Quick check",
                    prompt = "Where do Material 3 composables get their colors and text styles?",
                    options = listOf(
                        "Hardcoded in each composable",
                        "From MaterialTheme (colorScheme and typography)",
                        "From the AndroidManifest",
                        "From a network request",
                    ),
                    correctIndex = 1,
                    explanation = "MaterialTheme provides colorScheme, typography, and shapes so components stay consistent.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "ca-effects", title = "Side effects", summary = "Run non-UI work at the right time.",
            iconId = "layers", estMinutes = 7,
            steps = listOf(
                LearnStepDef.Concept(
                    "ca-effects-c", "Effects belong outside composition",
                    listOf(
                        text("A composable can run many times, so you must not start work (network calls, timers) directly in its body. **Side-effect APIs** run that work at controlled moments."),
                        text("**LaunchedEffect** runs a suspend block when it enters composition (and restarts if its key changes) — ideal for one-off loads:"),
                        code(
                            """
                            @Composable
                            fun Screen(userId: String) {
                                var name by remember { mutableStateOf("Loading…") }
                                LaunchedEffect(userId) {
                                    name = fetchName(userId)   // runs once per userId
                                }
                                Text(name)
                            }
                            """
                        ),
                        text("Below, a plain piece of state drives the UI — the same idea a `LaunchedEffect` would set. Tap to flip it:"),
                        composePreview(
                            """
                            import androidx.compose.foundation.layout.Column
                            import androidx.compose.foundation.layout.padding
                            import androidx.compose.material3.Button
                            import androidx.compose.material3.Text
                            import androidx.compose.runtime.Composable
                            import androidx.compose.runtime.getValue
                            import androidx.compose.runtime.mutableStateOf
                            import androidx.compose.runtime.remember
                            import androidx.compose.runtime.setValue
                            import androidx.compose.ui.Modifier
                            import androidx.compose.ui.tooling.preview.Preview
                            import androidx.compose.ui.unit.dp

                            @Composable
                            fun Status() {
                                var loaded by remember { mutableStateOf(false) }
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(if (loaded) "Ready" else "Loading…")
                                    Button(onClick = { loaded = !loaded }) {
                                        Text("Toggle")
                                    }
                                }
                            }

                            @Preview
                            @Composable
                            fun StatusPreview() {
                                Status()
                            }
                            """,
                            interactive = true,
                        ),
                        note("Other effects: `rememberCoroutineScope` (launch work from a callback) and `DisposableEffect` (clean up when leaving composition)."),
                    ),
                ),
                LearnStepDef.Quiz(
                    "ca-effects-q", "Quick check",
                    prompt = "Why not start a network call directly in a composable's body?",
                    options = listOf(
                        "Network calls are not allowed in Kotlin",
                        "A composable can recompose many times, so the call would fire repeatedly; LaunchedEffect scopes it",
                        "It would make the app faster",
                        "The body only runs once, so it's fine",
                    ),
                    correctIndex = 1,
                    explanation = "Composition can repeat; LaunchedEffect runs the work once per key instead of every recomposition.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "ca-anim", title = "Animation basics", summary = "Animate state changes smoothly.",
            iconId = "layers", estMinutes = 7,
            steps = listOf(
                LearnStepDef.Concept(
                    "ca-anim-c", "animate*AsState",
                    listOf(
                        text("The simplest animations come from `animate*AsState` functions: give them a **target** value that depends on state, and Compose animates from the old value to the new one whenever it changes."),
                        text("Here the box colour animates between two values as you toggle. **Edit** the colours or the size:"),
                        composePreview(
                            """
                            import androidx.compose.animation.animateColorAsState
                            import androidx.compose.foundation.background
                            import androidx.compose.foundation.layout.Box
                            import androidx.compose.foundation.layout.Column
                            import androidx.compose.foundation.layout.padding
                            import androidx.compose.foundation.layout.size
                            import androidx.compose.foundation.shape.RoundedCornerShape
                            import androidx.compose.material3.Button
                            import androidx.compose.material3.Text
                            import androidx.compose.runtime.Composable
                            import androidx.compose.runtime.getValue
                            import androidx.compose.runtime.mutableStateOf
                            import androidx.compose.runtime.remember
                            import androidx.compose.runtime.setValue
                            import androidx.compose.ui.Modifier
                            import androidx.compose.ui.draw.clip
                            import androidx.compose.ui.graphics.Color
                            import androidx.compose.ui.tooling.preview.Preview
                            import androidx.compose.ui.unit.dp

                            @Composable
                            fun ColorToggle() {
                                var on by remember { mutableStateOf(false) }
                                val color by animateColorAsState(
                                    if (on) Color(0xFF6750A4) else Color(0xFFB0BEC5)
                                )
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(88.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(color)
                                    )
                                    Button(onClick = { on = !on }) {
                                        Text("Toggle")
                                    }
                                }
                            }

                            @Preview
                            @Composable
                            fun ColorTogglePreview() {
                                ColorToggle()
                            }
                            """,
                            interactive = true,
                            caption = "Tap Toggle — the color animates",
                        ),
                    ),
                ),
                LearnStepDef.Quiz(
                    "ca-anim-q", "Quick check",
                    prompt = "What do animate*AsState functions do?",
                    options = listOf(
                        "Run an animation on a background thread",
                        "Animate a value toward a new target whenever the target changes",
                        "Play a video",
                        "Delay the whole composable",
                    ),
                    correctIndex = 1,
                    explanation = "You give a target value derived from state; the animated value eases from the old value to the new one.",
                ),
            ),
        ),
    ),
)

// ===========================================================================
// Java: More (JDK, Java-8 safe — interfaces, maps, generics)
// ===========================================================================

private fun javaMore() = LearnTrackDef(
    id = "java-more",
    title = "Java: More",
    subtitle = "Interfaces, maps, and generics",
    iconId = "java",
    accentColor = ACCENT_JAVA3,
    language = "java",
    category = "Java",
    level = "Intermediate",
    prerequisites = listOf("java-beyond"),
    lessons = listOf(
        LearnLessonDef(
            id = "jm-interface", title = "Interfaces", summary = "Program to a contract.",
            iconId = "java", estMinutes = 7,
            steps = listOf(
                LearnStepDef.Concept(
                    "jm-interface-c", "implements",
                    listOf(
                        text("An **interface** declares methods; a class `implements` it and provides the bodies:"),
                        code(
                            """
                            interface Greeter {
                                String greet();
                            }

                            class English implements Greeter {
                                public String greet() {
                                    return "Hi";
                                }
                            }
                            """,
                            "java",
                        ),
                    ),
                ),
                LearnStepDef.Interactive(
                    "jm-interface-i", "Implement Greeter",
                    listOf(text("Make `class English implements Greeter` whose `greet()` returns `\"Hi\"`, then print `new English().greet()`.")),
                    starterCode = """
                        interface Greeter {
                            String greet();
                        }

                        // Make class English implement Greeter, returning "Hi" from greet()

                        public class Main {
                            public static void main(String[] args) {
                                // Print new English().greet()
                            }
                        }
                    """,
                    language = "java",
                    hints = listOf("class English implements Greeter { public String greet() { return \"Hi\"; } }", "System.out.println(new English().greet());"),
                    solution = """
                        interface Greeter {
                            String greet();
                        }

                        class English implements Greeter {
                            public String greet() {
                                return "Hi";
                            }
                        }

                        public class Main {
                            public static void main(String[] args) {
                                System.out.println(new English().greet());
                            }
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "Hi", requireSource = listOf("interface Greeter", "implements Greeter", "new English(")),
                ),
            ),
        ),
        LearnLessonDef(
            id = "jm-map", title = "Maps", summary = "Look values up by key.",
            iconId = "java", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "jm-map-c", "HashMap",
                    listOf(
                        text("A `HashMap` stores key → value pairs. `put` adds one; `get` looks it up:"),
                        code(
                            """
                            import java.util.HashMap;

                            HashMap<String, Integer> map = new HashMap<>();
                            map.put("a", 1);
                            System.out.println(map.get("a"));  // 1
                            """,
                            "java",
                        ),
                    ),
                ),
                LearnStepDef.Interactive(
                    "jm-map-i", "Add the values",
                    listOf(text("Put `\"a\" -> 1` and `\"b\" -> 2` in a `HashMap`, then print `get(\"a\") + get(\"b\")` (`3`).")),
                    starterCode = """
                        import java.util.HashMap;

                        public class Main {
                            public static void main(String[] args) {
                                HashMap<String, Integer> map = new HashMap<>();
                                // Put "a"->1 and "b"->2, then print the sum of the two values
                            }
                        }
                    """,
                    language = "java",
                    hints = listOf("map.put(\"a\", 1); map.put(\"b\", 2);", "System.out.println(map.get(\"a\") + map.get(\"b\"));"),
                    solution = """
                        import java.util.HashMap;

                        public class Main {
                            public static void main(String[] args) {
                                HashMap<String, Integer> map = new HashMap<>();
                                map.put("a", 1);
                                map.put("b", 2);
                                System.out.println(map.get("a") + map.get("b"));
                            }
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "3", requireSource = listOf("HashMap", ".put(", ".get(")),
                ),
            ),
        ),
        LearnLessonDef(
            id = "jm-generics", title = "Generics", summary = "Write code that works for any type.",
            iconId = "java", estMinutes = 7,
            steps = listOf(
                LearnStepDef.Concept(
                    "jm-generics-c", "Type parameters",
                    listOf(
                        text("A **generic** method uses a type parameter `<T>` so it works for any type without casting:"),
                        code(
                            """
                            static <T> T firstOf(T[] items) {
                                return items[0];
                            }

                            String[] names = {"Sam", "Alex"};
                            System.out.println(firstOf(names));  // Sam
                            """,
                            "java",
                        ),
                    ),
                ),
                LearnStepDef.Interactive(
                    "jm-generics-i", "Return the first element",
                    listOf(text("Write a generic `static <T> T firstOf(T[] items)` that returns the first element, then print `firstOf` of `{\"Sam\", \"Alex\"}` (`Sam`).")),
                    starterCode = """
                        public class Main {
                            // Add a generic static <T> T firstOf(T[] items) returning items[0]

                            public static void main(String[] args) {
                                String[] names = {"Sam", "Alex"};
                                // Print firstOf(names)
                            }
                        }
                    """,
                    language = "java",
                    hints = listOf("static <T> T firstOf(T[] items) { return items[0]; }", "System.out.println(firstOf(names));"),
                    solution = """
                        public class Main {
                            static <T> T firstOf(T[] items) {
                                return items[0];
                            }

                            public static void main(String[] args) {
                                String[] names = {"Sam", "Alex"};
                                System.out.println(firstOf(names));
                            }
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "Sam", requireSource = listOf("<T>", "firstOf(")),
                ),
            ),
        ),
    ),
)

// ===========================================================================
// Java: Beyond Basics (intermediate — JDK only, fully interactive)
// ===========================================================================

private fun javaBeyond() = LearnTrackDef(
    id = "java-beyond",
    title = "Java: Beyond Basics",
    subtitle = "Classes, collections, and exceptions",
    iconId = "java",
    accentColor = ACCENT_JAVA2,
    language = "java",
    category = "Java",
    level = "Intermediate",
    prerequisites = listOf("java-basics"),
    lessons = listOf(
        LearnLessonDef(
            id = "jb-classes", title = "Classes & objects", summary = "Model things with your own types.",
            iconId = "java", estMinutes = 7,
            steps = listOf(
                LearnStepDef.Concept(
                    "jb-classes-c", "Defining a class",
                    listOf(
                        text("A **class** is a blueprint; an **object** is an instance created with `new`. Methods are the things it can do:"),
                        code(
                            """
                            class Dog {
                                String bark() {
                                    return "Woof";
                                }
                            }

                            Dog d = new Dog();
                            System.out.println(d.bark());  // Woof
                            """,
                            "java",
                        ),
                    ),
                ),
                LearnStepDef.Interactive(
                    "jb-classes-i", "A barking Dog",
                    listOf(text("Give `Dog` a `bark()` method that returns `\"Woof\"`, then print `new Dog().bark()`.")),
                    starterCode = """
                        class Dog {
                            // add a bark() method that returns "Woof"
                        }

                        public class Main {
                            public static void main(String[] args) {
                                // Print new Dog().bark()
                            }
                        }
                    """,
                    language = "java",
                    hints = listOf("String bark() { return \"Woof\"; }", "System.out.println(new Dog().bark());"),
                    solution = """
                        class Dog {
                            String bark() {
                                return "Woof";
                            }
                        }

                        public class Main {
                            public static void main(String[] args) {
                                System.out.println(new Dog().bark());
                            }
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "Woof", requireSource = listOf("class Dog", "new Dog(", "bark")),
                ),
            ),
        ),
        LearnLessonDef(
            id = "jb-list", title = "Lists", summary = "Grow a collection with ArrayList.",
            iconId = "java", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "jb-list-c", "ArrayList",
                    listOf(
                        text("An `ArrayList` is a resizable list. Add with `add`, count with `size`:"),
                        code(
                            """
                            import java.util.ArrayList;

                            ArrayList<String> list = new ArrayList<>();
                            list.add("a");
                            list.add("b");
                            System.out.println(list.size());  // 2
                            """,
                            "java",
                        ),
                    ),
                ),
                LearnStepDef.Interactive(
                    "jb-list-i", "Count the items",
                    listOf(text("Create an `ArrayList<String>`, add `\"a\"` and `\"b\"`, then print its `size()` (`2`).")),
                    starterCode = """
                        import java.util.ArrayList;

                        public class Main {
                            public static void main(String[] args) {
                                // Create an ArrayList<String>, add "a" and "b", print its size
                            }
                        }
                    """,
                    language = "java",
                    hints = listOf("ArrayList<String> list = new ArrayList<>();", "list.add(\"a\"); then print list.size()"),
                    solution = """
                        import java.util.ArrayList;

                        public class Main {
                            public static void main(String[] args) {
                                ArrayList<String> list = new ArrayList<>();
                                list.add("a");
                                list.add("b");
                                System.out.println(list.size());
                            }
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "2", requireSource = listOf("ArrayList", ".add(", ".size()")),
                ),
            ),
        ),
        LearnLessonDef(
            id = "jb-exceptions", title = "Exceptions", summary = "Recover from errors with try / catch.",
            iconId = "java", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "jb-exceptions-c", "try / catch",
                    listOf(
                        text("Code that might fail goes in `try`; you handle the failure in `catch`:"),
                        code(
                            """
                            try {
                                int zero = 0;
                                System.out.println(10 / zero);
                            } catch (ArithmeticException e) {
                                System.out.println("caught");
                            }
                            """,
                            "java",
                        ),
                        text("Dividing by zero throws an `ArithmeticException`, which the `catch` block handles instead of crashing the program."),
                    ),
                ),
                LearnStepDef.Interactive(
                    "jb-exceptions-i", "Catch the error",
                    listOf(text("Divide `10` by a variable holding `0` inside a `try`, and print `caught` from the `catch`.")),
                    starterCode = """
                        public class Main {
                            public static void main(String[] args) {
                                // Try 10 / zero, catch the exception, and print "caught"
                            }
                        }
                    """,
                    language = "java",
                    hints = listOf("Wrap the division in try { ... }", "catch (ArithmeticException e) { System.out.println(\"caught\"); }"),
                    solution = """
                        public class Main {
                            public static void main(String[] args) {
                                try {
                                    int zero = 0;
                                    System.out.println(10 / zero);
                                } catch (ArithmeticException e) {
                                    System.out.println("caught");
                                }
                            }
                        }
                    """,
                    check = ExerciseCheck(expectedOutput = "caught", requireSource = listOf("try", "catch")),
                ),
                LearnStepDef.Quiz(
                    "jb-exceptions-q", "Quick check",
                    prompt = "What happens to code after the failing line inside a try block?",
                    options = listOf(
                        "It runs anyway",
                        "It's skipped; control jumps to the matching catch",
                        "The program always crashes",
                        "It runs twice",
                    ),
                    correctIndex = 1,
                    explanation = "When an exception is thrown, the rest of the try is skipped and the matching catch runs.",
                ),
            ),
        ),
    ),
)

// ===========================================================================
// Android Basics (concept-only orientation — building blocks of an Android app)
// ===========================================================================

private fun androidBasics() = LearnTrackDef(
    id = "android-basics",
    title = "Android Basics",
    subtitle = "Activities, layouts, and the app lifecycle",
    iconId = "module.android",
    accentColor = ACCENT_ANDROID,
    language = "none",
    category = "Android",
    level = "Beginner",
    prerequisites = listOf("kotlin-basics"),
    lessons = listOf(
        LearnLessonDef(
            id = "and-activity", title = "Activities", summary = "The screens of your app.",
            iconId = "module.android", estMinutes = 5,
            steps = listOf(
                LearnStepDef.Concept(
                    "and-activity-c", "What is an Activity?",
                    listOf(
                        text("An **Activity** is a single screen in an Android app. It starts in `onCreate`, where you set the screen's layout with `setContentView`:"),
                        code(
                            """
                            class MainActivity : AppCompatActivity() {
                                override fun onCreate(savedInstanceState: Bundle?) {
                                    super.onCreate(savedInstanceState)
                                    setContentView(R.layout.activity_main)
                                }
                            }
                            """
                        ),
                        text("`R.layout.activity_main` refers to the XML layout in `res/layout/activity_main.xml` — Android generates the `R` class from your resources."),
                        text("Here's what a simple screen defined by that layout looks like — rendered live, right here:"),
                        preview(
                            """
                            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:orientation="vertical"
                                android:gravity="center_horizontal"
                                android:background="#FFF6F5FB"
                                android:padding="28dp">

                                <TextView
                                    android:layout_width="wrap_content"
                                    android:layout_height="wrap_content"
                                    android:text="Welcome"
                                    android:textSize="26sp"
                                    android:textStyle="bold"
                                    android:textColor="#FF1C1B1F" />

                                <TextView
                                    android:layout_width="wrap_content"
                                    android:layout_height="wrap_content"
                                    android:layout_marginTop="6dp"
                                    android:text="This is your first Activity"
                                    android:textSize="15sp"
                                    android:textColor="#FF6B6B70" />
                            </LinearLayout>
                            """,
                            caption = "The layout setContentView() would show",
                        ),
                        tip("Spin up a full Android project from the Explore tab's templates to see this wired end to end."),
                    ),
                ),
            ),
        ),
        LearnLessonDef(
            id = "and-layouts", title = "Layouts & views", summary = "Describe the UI in XML.",
            iconId = "module.android", estMinutes = 7,
            steps = listOf(
                LearnStepDef.Concept(
                    "and-layouts-c", "Views and view groups",
                    listOf(
                        text("A layout is a tree of **views** (a `TextView`, `Button`, …) held by **view groups** (`LinearLayout`, `ConstraintLayout`, …). You write it in XML:"),
                        code(
                            """
                            <LinearLayout
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:orientation="vertical">

                                <TextView
                                    android:layout_width="wrap_content"
                                    android:layout_height="wrap_content"
                                    android:text="Hello, Android!" />
                            </LinearLayout>
                            """,
                            "xml",
                        ),
                        text("`layout_width`/`layout_height` are required on every view — `match_parent` fills the parent, `wrap_content` is just big enough for the content."),
                        note("The editor gives you completion for Android widgets and attributes right inside layout XML."),
                    ),
                ),
                LearnStepDef.Concept(
                    "and-layouts-play", "Try it yourself",
                    listOf(
                        text("Below is a live layout. **Edit the XML** and watch the preview update instantly — try changing the `android:text`, the `orientation` to `horizontal`, or the button's `android:backgroundTint` colour."),
                        preview(
                            """
                            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:orientation="vertical"
                                android:padding="20dp"
                                android:background="#FFFFFFFF">

                                <TextView
                                    android:layout_width="wrap_content"
                                    android:layout_height="wrap_content"
                                    android:text="Sign in"
                                    android:textSize="22sp"
                                    android:textStyle="bold"
                                    android:textColor="#FF1C1B1F" />

                                <EditText
                                    android:layout_width="match_parent"
                                    android:layout_height="wrap_content"
                                    android:layout_marginTop="16dp"
                                    android:hint="Email"
                                    android:textColor="#FF1C1B1F" />

                                <Button
                                    android:layout_width="match_parent"
                                    android:layout_height="wrap_content"
                                    android:layout_marginTop="16dp"
                                    android:text="Continue"
                                    android:backgroundTint="#FF6750A4"
                                    android:textColor="#FFFFFFFF" />
                            </LinearLayout>
                            """,
                            interactive = true,
                        ),
                    ),
                ),
                LearnStepDef.Quiz(
                    "and-layouts-q", "Quick check",
                    prompt = "What does android:layout_width=\"match_parent\" do?",
                    options = listOf(
                        "Makes the view as small as its content",
                        "Makes the view fill the width of its parent",
                        "Hides the view",
                        "Sets the width to exactly 100dp",
                    ),
                    correctIndex = 1,
                    explanation = "match_parent stretches the view to fill its parent; wrap_content shrinks it to fit its content.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "and-widgets", title = "Common widgets", summary = "The building blocks of a screen.",
            iconId = "module.android", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "and-widgets-c", "A toolkit of views",
                    listOf(
                        text("Android ships a rich set of ready-made widgets. A few you'll use constantly:"),
                        text("• **TextView** — shows text\n• **EditText** — a text input field\n• **Button** — a tappable action\n• **ImageView** — shows an image"),
                        text("Stack a few together and you have a screen. Here they are, rendered live:"),
                        preview(
                            """
                            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:orientation="vertical"
                                android:padding="20dp"
                                android:background="#FFFFFFFF">

                                <TextView
                                    android:layout_width="wrap_content"
                                    android:layout_height="wrap_content"
                                    android:text="Create account"
                                    android:textSize="22sp"
                                    android:textStyle="bold"
                                    android:textColor="#FF1C1B1F" />

                                <TextView
                                    android:layout_width="wrap_content"
                                    android:layout_height="wrap_content"
                                    android:layout_marginTop="4dp"
                                    android:text="It only takes a minute"
                                    android:textSize="14sp"
                                    android:textColor="#FF6B6B70" />

                                <EditText
                                    android:layout_width="match_parent"
                                    android:layout_height="wrap_content"
                                    android:layout_marginTop="18dp"
                                    android:hint="Full name"
                                    android:textColor="#FF1C1B1F" />

                                <EditText
                                    android:layout_width="match_parent"
                                    android:layout_height="wrap_content"
                                    android:layout_marginTop="10dp"
                                    android:hint="Email"
                                    android:textColor="#FF1C1B1F" />

                                <Button
                                    android:layout_width="match_parent"
                                    android:layout_height="wrap_content"
                                    android:layout_marginTop="18dp"
                                    android:text="Sign up"
                                    android:backgroundTint="#FF6750A4"
                                    android:textColor="#FFFFFFFF" />
                            </LinearLayout>
                            """,
                            caption = "TextView, EditText and Button working together",
                        ),
                        tip("Every widget accepts the shared `layout_*`, `padding`, and `background` attributes on top of its own."),
                    ),
                ),
                LearnStepDef.Quiz(
                    "and-widgets-q", "Quick check",
                    prompt = "Which widget would you use for a single-line text input?",
                    options = listOf("TextView", "Button", "EditText", "ImageView"),
                    correctIndex = 2,
                    explanation = "EditText is the editable text field; TextView only displays text.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "and-lifecycle", title = "The lifecycle", summary = "How the system drives your screen.",
            iconId = "module.android", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "and-lifecycle-c", "Lifecycle callbacks",
                    listOf(
                        text("Android drives an Activity through **lifecycle callbacks** as it comes and goes:"),
                        code(
                            """
                            onCreate()   // screen created — set up UI
                            onStart()    // becoming visible
                            onResume()   // in the foreground, interactive
                            onPause()    // losing focus — save quick state
                            onStop()     // no longer visible
                            onDestroy()  // screen going away
                            """,
                            "plain",
                        ),
                        text("Override the ones you need. For example, pause a game in `onPause` and resume it in `onResume`, so it behaves when the user switches apps."),
                        tip("Every callback pairs up: onStart/onStop, onResume/onPause, onCreate/onDestroy."),
                    ),
                ),
            ),
        ),
        LearnLessonDef(
            id = "and-manifest", title = "The manifest", summary = "Declare what your app is and needs.",
            iconId = "manifest", estMinutes = 5,
            steps = listOf(
                LearnStepDef.Concept(
                    "and-manifest-c", "AndroidManifest.xml",
                    listOf(
                        text("`AndroidManifest.xml` tells the system about your app: its components (activities), the launcher screen, and the permissions it needs:"),
                        code(
                            """
                            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                                <uses-permission android:name="android.permission.INTERNET" />

                                <application android:label="My App">
                                    <activity android:name=".MainActivity" android:exported="true">
                                        <intent-filter>
                                            <action android:name="android.intent.action.MAIN" />
                                            <category android:name="android.intent.category.LAUNCHER" />
                                        </intent-filter>
                                    </activity>
                                </application>
                            </manifest>
                            """,
                            "xml",
                        ),
                        text("The `<intent-filter>` with `MAIN` + `LAUNCHER` marks the Activity that opens when you tap the app icon."),
                    ),
                ),
            ),
        ),
        LearnLessonDef(
            id = "and-build-screen", title = "Build a sign-in screen", kind = "capstone",
            summary = "Write a real layout from scratch and watch it render.",
            iconId = "module.android", estMinutes = 10,
            steps = listOf(
                LearnStepDef.Interactive(
                    "and-build-button", "Add a button",
                    listOf(
                        text("Warm up: the layout below has a title. Add a **Button** inside the `LinearLayout` with the id `hello` and the text `Say hello`. The preview updates as you type."),
                        tip("An id is written `android:id=\"@+id/hello\"`. Every view also needs `layout_width` and `layout_height`."),
                    ),
                    starterCode = """
                        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:orientation="vertical"
                            android:padding="20dp"
                            android:background="#FFFFFFFF">

                            <TextView
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:text="Welcome"
                                android:textSize="22sp"
                                android:textColor="#FF1C1B1F" />

                        </LinearLayout>
                    """,
                    language = "xml-layout",
                    hints = listOf(
                        "Start a new tag under the TextView: <Button ... />",
                        "Give it android:id=\"@+id/hello\" and android:text=\"Say hello\".",
                        "Don't forget android:layout_width=\"wrap_content\" and android:layout_height=\"wrap_content\".",
                    ),
                    solution = """
                        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:orientation="vertical"
                            android:padding="20dp"
                            android:background="#FFFFFFFF">

                            <TextView
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:text="Welcome"
                                android:textSize="22sp"
                                android:textColor="#FF1C1B1F" />

                            <Button
                                android:id="@+id/hello"
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:layout_marginTop="16dp"
                                android:text="Say hello" />

                        </LinearLayout>
                    """,
                    check = ExerciseCheck(
                        layout = LayoutCheck(
                            listOf(
                                ViewRule(
                                    "Button", "a Button with id hello inside the LinearLayout",
                                    id = "hello", parent = "LinearLayout",
                                ),
                                ViewRule("Button", "the button's text is \"Say hello\"", attrs = mapOf("android:text" to "Say hello")),
                            ),
                        ),
                    ),
                ),
                LearnStepDef.Interactive(
                    "and-build-signin", "A sign-in form",
                    listOf(
                        text("Now the real thing. Build a sign-in form inside a **vertical** `LinearLayout`:"),
                        text("• a `TextView` title\n• two `EditText` fields, for email and password\n• a `Button` with the id `signIn`"),
                        note("Use `android:hint` to label a field, and `android:inputType=\"textPassword\"` to hide what is typed into the password field."),
                    ),
                    starterCode = """
                        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:padding="20dp"
                            android:background="#FFFFFFFF">

                            <!-- Build the form here -->

                        </LinearLayout>
                    """,
                    language = "xml-layout",
                    hints = listOf(
                        "The LinearLayout needs android:orientation=\"vertical\" so the views stack.",
                        "An EditText needs a width and height like any view; match_parent makes it fill the row.",
                        "The button's id is written android:id=\"@+id/signIn\".",
                    ),
                    solution = """
                        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:orientation="vertical"
                            android:padding="20dp"
                            android:background="#FFFFFFFF">

                            <TextView
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:text="Sign in"
                                android:textSize="22sp"
                                android:textStyle="bold"
                                android:textColor="#FF1C1B1F" />

                            <EditText
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:layout_marginTop="16dp"
                                android:hint="Email"
                                android:textColor="#FF1C1B1F" />

                            <EditText
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:layout_marginTop="8dp"
                                android:hint="Password"
                                android:inputType="textPassword"
                                android:textColor="#FF1C1B1F" />

                            <Button
                                android:id="@+id/signIn"
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:layout_marginTop="16dp"
                                android:text="Sign in" />

                        </LinearLayout>
                    """,
                    check = ExerciseCheck(
                        layout = LayoutCheck(
                            listOf(
                                ViewRule("LinearLayout", "a vertical LinearLayout", attrs = mapOf("android:orientation" to "vertical")),
                                ViewRule("TextView", "a TextView title", parent = "LinearLayout"),
                                ViewRule("EditText", "two EditText fields", parent = "LinearLayout", count = 2),
                                ViewRule("Button", "a Button with id signIn", id = "signIn", parent = "LinearLayout"),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    ),
)

// ===========================================================================
// Getting Started (concept-only orientation)
// ===========================================================================

private fun gettingStarted() = LearnTrackDef(
    id = "getting-started",
    title = "Getting Started",
    subtitle = "How projects, modules, and builds fit together",
    iconId = "sparkle",
    accentColor = ACCENT_START,
    language = "none",
    category = "Get started",
    level = "Beginner",
    lessons = listOf(
        LearnLessonDef(
            id = "gs-welcome", title = "Welcome to CodeAssist", summary = "What you can build here.",
            iconId = "sparkle", estMinutes = 3,
            steps = listOf(
                LearnStepDef.Concept(
                    "gs-welcome-c", "A full IDE on your device",
                    listOf(
                        text("CodeAssist is a complete IDE that **edits, builds, and runs** Java and Kotlin projects right on your device — no desktop required."),
                        text("You get smart code completion, live error checking, a real build system, and for Android projects a full **compile → dex → package → sign** pipeline that produces an installable APK."),
                        tip("Head to the **Store** tab to start a new project from a ready-made template."),
                    ),
                ),
            ),
        ),
        LearnLessonDef(
            id = "gs-modules", title = "Projects and modules", summary = "How your code is organized.",
            iconId = "pkg", estMinutes = 4,
            steps = listOf(
                LearnStepDef.Concept(
                    "gs-modules-c", "The building blocks",
                    listOf(
                        text("A **project** is your whole app. It contains one or more **modules** — independently buildable units of code."),
                        text("Each module has **source sets** (like `main`) that hold your source roots, for example:"),
                        code(
                            """
                            app/
                              src/main/java/     ← your Java code
                              src/main/kotlin/   ← your Kotlin code
                              src/main/res/      ← Android resources
                            """,
                            "plain",
                        ),
                        note("Modules can depend on each other. An `app` module often depends on a `library` module for shared code."),
                    ),
                ),
            ),
        ),
        LearnLessonDef(
            id = "gs-build", title = "Building and running", summary = "From source to a running app.",
            iconId = "hammer", estMinutes = 4,
            steps = listOf(
                LearnStepDef.Concept(
                    "gs-build-c", "The Run button",
                    listOf(
                        text("Tap **Run** to build and launch. Under the hood the build compiles your sources, resolves dependencies, and — for Android — dexes and packages an APK."),
                        text("Builds are **incremental**: after the first build, only what you changed is recompiled, so later runs are fast."),
                        tip("The build console shows each step, streamed logs, and any errors as you go."),
                    ),
                ),
            ),
        ),
    ),
)
