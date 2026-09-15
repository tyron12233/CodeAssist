package dev.ide.android.support

import dev.ide.android.support.tasks.InjectAppLogProviderTask
import dev.ide.build.TaskName
import dev.ide.build.TaskResult
import dev.ide.build.engine.SimpleTaskContext
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The manifest rewrite on its own, without an SDK or a build.
 *
 * [AppLogInjectTest] proves the whole pipeline and needs an installed SDK to do it, which makes it the wrong
 * place for the cases that are about one decision: whether this app can be instrumented at all.
 */
class InjectAppLogProviderTaskTest {

    /**
     * An app declaring `hasCode="false"` loads no dex, so a `<provider>` naming a Java class cannot resolve
     * and the app dies on launch with `ClassNotFoundException` before a line of its own code runs. That is
     * the shape of every NativeActivity app, so this is not a corner case; it is the native template.
     */
    @Test
    fun aNativeActivityAppIsLeftAlone() {
        val out = run(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.game">
                <application android:label="Game" android:hasCode="false">
                    <activity android:name="android.app.NativeActivity" android:exported="true">
                        <meta-data android:name="android.app.lib_name" android:value="game" />
                    </activity>
                </application>
            </manifest>
            """.trimIndent()
        )
        assertFalse(PROVIDER in out, "a hasCode=false app must not be given a provider it cannot load:\n$out")
        assertFalse("<queries>" in out, "nothing is added to a manifest that is passed through")
        assertTrue("android.app.NativeActivity" in out, "the manifest is passed through, not emptied")
    }

    @Test
    fun anOrdinaryAppIsInstrumented() {
        val out = run(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
                <application android:label="App">
                    <activity android:name=".MainActivity" android:exported="true" />
                </application>
            </manifest>
            """.trimIndent()
        )
        assertTrue(PROVIDER in out, "an ordinary app gets the log bridge:\n$out")
        assertTrue(SINK_ACTION in out, "and the <queries> intent that makes the sink visible on API 30+")
    }

    /** `hasCode="true"` written out explicitly is an ordinary app that said so. */
    @Test
    fun anExplicitHasCodeTrueIsStillInstrumented() {
        val out = run(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
                <application android:label="App" android:hasCode="true" />
            </manifest>
            """.trimIndent()
        )
        assertTrue(PROVIDER in out, out)
    }

    private fun run(manifest: String): String {
        val dir: Path = Files.createTempDirectory("applog-inject")
        val merged = dir.resolve("merged/AndroidManifest.xml").also { it.parent.createDirectories() }
        merged.writeText(manifest)
        val out = dir.resolve("instrumented/AndroidManifest.xml")
        val result = runBlocking {
            InjectAppLogProviderTask(
                name = TaskName(":app:injectAppLogProviderDebug"),
                mergedManifest = merged,
                providerClass = PROVIDER,
                authority = "com.example.app.idelog",
                sinkAction = SINK_ACTION,
                outManifest = out,
            ).execute(SimpleTaskContext())
        }
        assertEquals(TaskResult.Success, result)
        return Files.readString(out)
    }

    private companion object {
        const val PROVIDER = "dev.ide.applog.IdeLogBridgeProvider"
        const val SINK_ACTION = "dev.ide.applog.SINK"
    }
}
