package dev.ide.android.support.templates

import dev.ide.android.support.AndroidFacet
import dev.ide.android.support.AndroidFeatureDependencies
import dev.ide.android.support.BuildFeatures
import dev.ide.model.BuildSystemId
import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.model.template.TemplateDependency
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter

/**
 * Built-in **Jetpack Compose sample games** (Snake, Tic-Tac-Toe, Memory Match, 2048) — complete, polished,
 * runnable Compose apps listed under "Sample projects" in the store. Each scaffolds a single `app`
 * (android-app) module with the Compose compiler on ([BuildFeatures.compose]) and the Compose runtime +
 * Material 3 dependencies declared, then copies the game's Kotlin sources verbatim from bundled classpath
 * resources under `resources/samples/<id>/…` (so the example code stays clean and idiomatic to maintain).
 *
 * Sample ids are prefixed `sample-` so the store buckets them under "Sample projects" (not "Starter
 * templates"); they otherwise flow through the exact same Create-Project path as any other template.
 */
internal object ComposeSampleSupport {

    /** The Compose runtime + Material 3 + preview tooling, attached to the generated `app` module. */
    val composeDependencies: List<TemplateDependency> =
        AndroidFeatureDependencies.COMPOSE.map { TemplateDependency("app", it) }

    /** Read a bundled sample resource's raw bytes, or fail loudly (a missing sample is a build/packaging bug). */
    private fun readResourceBytes(path: String): ByteArray =
        ComposeSampleSupport::class.java.classLoader.getResourceAsStream(path)
            ?.use { it.readBytes() }
            ?: error("Missing bundled sample resource: $path")

    /** True if [bytes] has a NUL in its first block — the same "not text" sniff the editor uses. */
    private fun looksBinary(bytes: ByteArray): Boolean =
        (0 until minOf(bytes.size, 8000)).any { bytes[it].toInt() == 0 }

    /** Copy a bundled sample file: byte-exact for binary assets, UTF-8 text otherwise (output unchanged). */
    private fun copyResource(scaffold: ProjectScaffold, resourcePath: String, dest: String) {
        val bytes = readResourceBytes(resourcePath)
        if (looksBinary(bytes)) scaffold.writeBytes(dest, bytes)
        else scaffold.writeText(dest, String(bytes, Charsets.UTF_8))
    }

    /**
     * Scaffold a single-module Jetpack Compose app: the project + the `app` module (Compose facet), the
     * manifest, `res/` (strings/colors/theme + a game-themed launcher icon), then the game's bundled Kotlin
     * [sources] (paths relative to both the sample resource root and the project root). The sample's own fixed
     * [pkg] is the module namespace so the manifest, `R`, and the bundled sources' `package` all agree.
     *
     * [icon] gives the launcher icon its game-specific background color and foreground artwork (the generic
     * adaptive-icon / legacy wrappers are reused from [AndroidAppAssets]).
     */
    fun generate(
        scaffold: ProjectScaffold,
        args: TemplateArgs,
        sampleId: String,
        pkg: String,
        sources: List<String>,
        icon: LauncherIcon,
    ) {
        scaffold.workspace.beginModification().apply {
            addProject(args.name, BuildSystemId.NATIVE, scaffold.rootDir)
            commit()
        }
        scaffold.workspace.projects.first { it.name == args.name }.beginModification().apply {
            addModule("app", scaffold.moduleType("android-app")).apply {
                languageLevel = scaffold.languageLevel
                putFacet(
                    AndroidFacet(
                        namespace = pkg,
                        compileSdk = AndroidTemplateSupport.COMPILE_SDK,
                        minSdk = 24,
                        targetSdk = AndroidTemplateSupport.COMPILE_SDK,
                        buildFeatures = BuildFeatures(compose = true),
                    ),
                )
            }
            commit()
        }

        scaffold.writeText("app/proguard-rules.pro", AndroidTemplateSupport.PROGUARD_RULES_PRO)
        scaffold.writeText(
            "app/src/main/AndroidManifest.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$pkg">
                <application
                    android:allowBackup="true"
                    android:icon="@mipmap/ic_launcher"
                    android:label="@string/app_name"
                    android:roundIcon="@mipmap/ic_launcher_round"
                    android:supportsRtl="true"
                    android:theme="@style/Theme.App">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
            """,
        )
        scaffold.writeText(
            "app/src/main/res/values/strings.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">${args.name}</string>
            </resources>
            """,
        )
        scaffold.writeText(
            "app/src/main/res/values/colors.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <color name="ic_launcher_background">${icon.background}</color>
            </resources>
            """,
        )
        // A NoActionBar framework theme — Compose handles its own theming, so no Material XML theme is needed.
        scaffold.writeText(
            "app/src/main/res/values/themes.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <style name="Theme.App" parent="android:Theme.Material.Light.NoActionBar"/>
            </resources>
            """,
        )
        // Reuse the generic adaptive-icon / legacy wrappers + the solid-color background drawable, but swap in
        // the game's own foreground artwork so each sample gets a distinct, on-theme launcher icon.
        for ((rel, content) in AndroidAppAssets.launcherIconResFiles) {
            val body = if (rel == "drawable/ic_launcher_foreground.xml") icon.foreground else content
            scaffold.writeText("app/src/main/res/$rel", body)
        }
        for (rel in sources) copyResource(scaffold, "samples/$sampleId/$rel", rel)
    }
}

/** A launcher icon: a solid [background] color (an `#RRGGBB`) behind a foreground [foreground] `<vector>`. */
internal class LauncherIcon(val background: String, val foreground: String)

/**
 * Builds the hand-authored launcher-icon foregrounds for the sample games as `<vector>` drawables. Everything
 * is drawn in the 108x108 adaptive-icon viewport, kept inside the centre safe zone so nothing is clipped.
 */
internal object SampleIcons {

    val SNAKE = LauncherIcon(
        background = "#0B1020",
        foreground = foregroundVector(
            // Body: a C-shaped chain of rounded squares, brighter head, plus a red apple.
            roundRect(30, 30, 15, 5, "#00E676") +
                roundRect(45, 30, 15, 5, "#00E676") +
                roundRect(60, 30, 15, 5, "#00E676") +
                roundRect(60, 45, 15, 5, "#00E676") +
                roundRect(60, 60, 15, 5, "#00E676") +
                roundRect(45, 60, 15, 5, "#00E676") +
                roundRect(30, 60, 15, 5, "#69F0AE") +
                circle(34, 64, 2, "#0B1020") +
                circle(38, 52, 6, "#FF5252"),
        ),
    )

    val TIC_TAC_TOE = LauncherIcon(
        background = "#0F172A",
        foreground = foregroundVector(
            // A cyan X beside a pink O.
            stroke("M30,36 L52,58", "#22D3EE", 10) +
                stroke("M52,36 L30,58", "#22D3EE", 10) +
                stroke("M57,47 a13,13 0 1,0 26,0 a13,13 0 1,0 -26,0", "#F472B6", 10),
        ),
    )

    val MEMORY = LauncherIcon(
        background = "#6D28D9",
        foreground = foregroundVector(
            // A 2x2 card grid; the diagonal pair matched (green), the others face-down (white).
            roundRect(30, 30, 20, 5, "#34D399") +
                roundRect(58, 30, 20, 5, "#F8FAFC") +
                roundRect(30, 58, 20, 5, "#F8FAFC") +
                roundRect(58, 58, 20, 5, "#34D399"),
        ),
    )

    val GAME_2048 = LauncherIcon(
        background = "#BBADA0",
        foreground = foregroundVector(
            // Four mini tiles in the game's warm palette.
            roundRect(30, 30, 20, 4, "#EEE4DA") +
                roundRect(58, 30, 20, 4, "#EDCF72") +
                roundRect(30, 58, 20, 4, "#F2B179") +
                roundRect(58, 58, 20, 4, "#F65E3B"),
        ),
    )

    // Built by concatenation (not an indented triple-quote) so the `<?xml` declaration stays at column 0 after
    // the scaffold's trimIndent() — a leading-space before the declaration is invalid XML aapt2 rejects.
    private fun foregroundVector(paths: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
            "    android:width=\"108dp\" android:height=\"108dp\"\n" +
            "    android:viewportWidth=\"108\" android:viewportHeight=\"108\">\n" +
            paths +
            "</vector>\n"

    /** A filled rounded square (top-left [x],[y], side [s], corner [r]) as a `<path>`. */
    private fun roundRect(x: Int, y: Int, s: Int, r: Int, fill: String): String {
        val inner = s - 2 * r
        val data = "M${x + r},$y h$inner q$r,0 $r,$r v$inner q0,$r -$r,$r h-$inner q-$r,0 -$r,-$r v-$inner q0,-$r $r,-$r z"
        return "    <path android:fillColor=\"$fill\" android:pathData=\"$data\"/>\n"
    }

    /** A filled circle centered at [cx],[cy] with radius [r]. */
    private fun circle(cx: Int, cy: Int, r: Int, fill: String): String {
        val data = "M${cx - r},$cy a$r,$r 0 1,0 ${2 * r},0 a$r,$r 0 1,0 -${2 * r},0 z"
        return "    <path android:fillColor=\"$fill\" android:pathData=\"$data\"/>\n"
    }

    /** A round-capped stroked path (no fill). */
    private fun stroke(data: String, color: String, width: Int): String =
        "    <path android:strokeColor=\"$color\" android:strokeWidth=\"$width\" " +
            "android:strokeLineCap=\"round\" android:strokeLineJoin=\"round\" android:pathData=\"$data\"/>\n"
}

/** Snake — a Canvas-drawn Snake game with swipe controls, a growing body, food, and a live score. */
object SnakeSampleTemplate : ProjectTemplate {
    override val id = TemplateId("sample-snake")
    override val displayName = "Snake"
    override val description = "The classic Snake game, drawn on a Compose Canvas with swipe controls, a live score, and a neon look."
    override val category = TemplateCategory.ANDROID
    override val iconId = "module.android"

    override fun parameters(): List<TemplateParameter> = emptyList()
    override fun dependencies(args: TemplateArgs): List<TemplateDependency> = ComposeSampleSupport.composeDependencies

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        ComposeSampleSupport.generate(
            scaffold, args, sampleId = "snake", pkg = "com.example.snake",
            sources = listOf(
                "app/src/main/kotlin/com/example/snake/MainActivity.kt",
                "app/src/main/kotlin/com/example/snake/SnakeGame.kt",
                "README.md",
            ),
            icon = SampleIcons.SNAKE,
        )
    }
}

/** Tic-Tac-Toe — a two-player Material 3 board with animated marks and a highlighted winning line. */
object TicTacToeSampleTemplate : ProjectTemplate {
    override val id = TemplateId("sample-tictactoe")
    override val displayName = "Tic-Tac-Toe"
    override val description = "A two-player Tic-Tac-Toe game with animated marks, a highlighted winning line, and Material 3 theming."
    override val category = TemplateCategory.ANDROID
    override val iconId = "module.android"

    override fun parameters(): List<TemplateParameter> = emptyList()
    override fun dependencies(args: TemplateArgs): List<TemplateDependency> = ComposeSampleSupport.composeDependencies

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        ComposeSampleSupport.generate(
            scaffold, args, sampleId = "tictactoe", pkg = "com.example.tictactoe",
            sources = listOf(
                "app/src/main/kotlin/com/example/tictactoe/MainActivity.kt",
                "app/src/main/kotlin/com/example/tictactoe/TicTacToeGame.kt",
                "README.md",
            ),
            icon = SampleIcons.TIC_TAC_TOE,
        )
    }
}

/** Memory Match — a grid of emoji cards with a 3D flip animation, match logic, and move/timer counters. */
object MemoryMatchSampleTemplate : ProjectTemplate {
    override val id = TemplateId("sample-memory")
    override val displayName = "Memory Match"
    override val description = "A memory card game with a 3D flip animation, match logic, move and timer counters, and a colorful UI."
    override val category = TemplateCategory.ANDROID
    override val iconId = "module.android"

    override fun parameters(): List<TemplateParameter> = emptyList()
    override fun dependencies(args: TemplateArgs): List<TemplateDependency> = ComposeSampleSupport.composeDependencies

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        ComposeSampleSupport.generate(
            scaffold, args, sampleId = "memory", pkg = "com.example.memory",
            sources = listOf(
                "app/src/main/kotlin/com/example/memory/MainActivity.kt",
                "app/src/main/kotlin/com/example/memory/MemoryGame.kt",
                "README.md",
            ),
            icon = SampleIcons.MEMORY,
        )
    }
}

/** 2048 — the swipe-to-merge tile puzzle with animated tile colors and a score/best tracker. */
object Game2048SampleTemplate : ProjectTemplate {
    override val id = TemplateId("sample-2048")
    override val displayName = "2048"
    override val description = "The 2048 tile puzzle: swipe to merge matching tiles, race to 2048, with animated tiles and score tracking."
    override val category = TemplateCategory.ANDROID
    override val iconId = "module.android"

    override fun parameters(): List<TemplateParameter> = emptyList()
    override fun dependencies(args: TemplateArgs): List<TemplateDependency> = ComposeSampleSupport.composeDependencies

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        ComposeSampleSupport.generate(
            scaffold, args, sampleId = "game2048", pkg = "com.example.game2048",
            sources = listOf(
                "app/src/main/kotlin/com/example/game2048/MainActivity.kt",
                "app/src/main/kotlin/com/example/game2048/Game2048.kt",
                "README.md",
            ),
            icon = SampleIcons.GAME_2048,
        )
    }
}
