package dev.ide.core.gradle

import dev.ide.model.Coordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The build-file edits behind the Gradle [dev.ide.model.sync.BuildFileWriter]: a dependency added in the IDE
 * is declared in the script that owns the model, so the next sync re-derives it instead of dropping it.
 */
class GradleDependencyEditsTest {

    private val okhttp = Coordinate("com.squareup.okhttp3", "okhttp", "4.12.0")

    private val gdxNatives =
        Coordinate("com.badlogicgames.gdx", "gdx-platform", "1.14.2", "natives-arm64-v8a")

    /** A configuration Gradle does not provide has to be created before it can be declared into, or the
     *  script the IDE just wrote no longer builds. */
    @Test
    fun creatingTheNativesConfigurationInAKotlinDslScriptThatHasNone() {
        val script = """
            plugins {
                id("com.android.application")
            }

            dependencies {
                implementation("com.badlogicgames.gdx:gdx:1.14.2")
            }
        """.trimIndent() + "\n"

        val added = assertNotNull(
            GradleDependencyEdits.add(script, kts = true, configuration = "natives", coordinate = gdxNatives, byName = true),
        )
        val updated = GradleDependencyEdits.ensureConfiguration(added, kts = true, configuration = "natives")

        assertEquals(
            """
                plugins {
                    id("com.android.application")
                }

                configurations {
                    create("natives")
                }

                dependencies {
                    implementation("com.badlogicgames.gdx:gdx:1.14.2")
                    "natives"("com.badlogicgames.gdx:gdx-platform:1.14.2:natives-arm64-v8a")
                }
            """.trimIndent() + "\n",
            updated,
        )
    }

    @Test
    fun anExistingConfigurationsBlockGainsOnlyTheMissingName() {
        val script = """
            configurations {
                create("tools")
            }

            dependencies {
                implementation("g:a:1.0")
            }
        """.trimIndent() + "\n"

        val once = GradleDependencyEdits.ensureConfiguration(script, kts = true, configuration = "natives")
        assertTrue("""create("natives")""" in once, once)
        assertTrue("""create("tools")""" in once, "the script's own configuration must survive: $once")
        // Idempotent: declaring a second natives artifact must not create the configuration twice.
        assertEquals(once, GradleDependencyEdits.ensureConfiguration(once, kts = true, configuration = "natives"))
    }

    @Test
    fun groovyCreatesTheConfigurationByBareNameAndDeclaresItDirectly() {
        val script = """
            dependencies {
                implementation 'g:a:1.0'
            }
        """.trimIndent() + "\n"

        val added = assertNotNull(
            GradleDependencyEdits.add(script, kts = false, configuration = "natives", coordinate = gdxNatives, byName = true),
        )
        val updated = GradleDependencyEdits.ensureConfiguration(added, kts = false, configuration = "natives")

        assertEquals(
            """
                configurations {
                    natives
                }

                dependencies {
                    implementation 'g:a:1.0'
                    natives 'com.badlogicgames.gdx:gdx-platform:1.14.2:natives-arm64-v8a'
                }
            """.trimIndent() + "\n",
            updated,
        )
    }

    @Test
    fun addsToAnExistingKotlinDslBlockKeepingIndentation() {
        val script = """
            plugins {
                id("com.android.application")
            }

            dependencies {
                implementation("androidx.core:core-ktx:1.13.1")
            }
        """.trimIndent() + "\n"

        val updated = GradleDependencyEdits.add(script, kts = true, configuration = "implementation", coordinate = okhttp)

        assertTrue(updated != null)
        assertEquals(
            """
                plugins {
                    id("com.android.application")
                }

                dependencies {
                    implementation("androidx.core:core-ktx:1.13.1")
                    implementation("com.squareup.okhttp3:okhttp:4.12.0")
                }
            """.trimIndent() + "\n",
            updated,
        )
    }

    @Test
    fun addsAGroovyBlockWhenTheScriptHasNone() {
        val script = "apply plugin: 'java-library'\n"

        val updated = GradleDependencyEdits.add(script, kts = false, configuration = "api", coordinate = okhttp)

        assertEquals(
            "apply plugin: 'java-library'\n\ndependencies {\n    api 'com.squareup.okhttp3:okhttp:4.12.0'\n}\n",
            updated,
        )
    }

    @Test
    fun addingAnAlreadyDeclaredCoordinateIsANoOpWhateverTheVersion() {
        val script = "dependencies {\n    implementation(\"com.squareup.okhttp3:okhttp:4.10.0\")\n}\n"

        assertNull(
            GradleDependencyEdits.add(script, kts = true, configuration = "implementation", coordinate = okhttp),
            "the declaration is matched on group:name, so a different version still counts as declared",
        )
    }

    @Test
    fun removesTheDeclarationAndLeavesTheRestUntouched() {
        val script = """
            dependencies {
                implementation("androidx.core:core-ktx:1.13.1")
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
                testImplementation("junit:junit:4.13.2")
            }
        """.trimIndent() + "\n"

        val updated = GradleDependencyEdits.remove(script, okhttp)

        assertEquals(
            """
                dependencies {
                    implementation("androidx.core:core-ktx:1.13.1")
                    testImplementation("junit:junit:4.13.2")
                }
            """.trimIndent() + "\n",
            updated,
        )
    }

    @Test
    fun commentsNeverDriveAnEdit() {
        // A coordinate mentioned only in a comment is not a declaration, and a brace inside one must not
        // confuse the block scan.
        val script = """
            // dependencies { implementation("com.squareup.okhttp3:okhttp:4.12.0") }
            dependencies {
                implementation("androidx.core:core-ktx:1.13.1")
            }
        """.trimIndent() + "\n"

        assertNull(GradleDependencyEdits.remove(script, okhttp), "a commented-out declaration is not removable")
        val updated = GradleDependencyEdits.add(script, kts = true, configuration = "implementation", coordinate = okhttp)
        assertTrue(
            updated != null && updated.contains("    implementation(\"com.squareup.okhttp3:okhttp:4.12.0\")\n}"),
            "the entry lands in the real block: $updated",
        )
    }

    @Test
    fun removingSomethingNotDeclaredReportsNoChange() {
        assertNull(GradleDependencyEdits.remove("dependencies {\n}\n", okhttp))
        assertNull(GradleDependencyEdits.remove("plugins {\n}\n", okhttp), "no dependencies block at all")
    }
}
