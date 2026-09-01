package dev.ide.core.gradle

import dev.ide.model.Coordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The build-file edits behind the Gradle [dev.ide.model.sync.BuildFileWriter]: a dependency added in the IDE
 * is declared in the script that owns the model, so the next sync re-derives it instead of dropping it.
 */
class GradleDependencyEditsTest {

    private val okhttp = Coordinate("com.squareup.okhttp3", "okhttp", "4.12.0")

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
