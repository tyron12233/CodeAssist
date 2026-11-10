package dev.ide.android.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room's annotation processor runs through KSP on the device. KSP and the processor are bundled jars dexed at
 * runtime and loaded with the IDE's classloader as parent, so they link the IDE's Kotlin compiler and Analysis
 * API BY NAME: a renamed public member there breaks this and nothing else.
 *
 * The processor turns on when its runtime is a directly declared dependency, so the test declares
 * `room-runtime` in module.toml (as a user would through the Dependencies screen), adds an entity, a DAO and a
 * database, and builds.
 */
@RunWith(AndroidJUnit4::class)
class KspRoomTest : E2eTest() {

    @Test
    fun roomGeneratesItsDatabaseThroughKsp() {
        val name = uniqueName("Room")
        createProject("Android App", name, listOf("Kotlin"))
        val dir = projectDir(name)
        // Read while the app runs: what it just wrote may not be visible to the shell yet.
        val toml = "$dir/app/module.toml"
        val declared = readDeviceFile(toml)

        val main = poll(60_000, 1_000) {
            shell("find $dir/app/src/main -name MainActivity.kt").lines().firstOrNull { it.isNotBlank() }?.trim()
        } ?: error("no MainActivity.kt (was the Kotlin option picked?): find=${shell("find $dir/app/src/main -type f")} ls=${shell("ls -R $dir/app/src")} top=${shell("ls $dir $dir/app")}")
        // A file the shell CREATES in the app's storage belongs to the shell and the app can't read it (KSP then
        // dies on EACCES); overwriting one the app created keeps it the app's. So the Room classes go into the
        // template's own MainActivity.kt, fully qualified so its imports can stay as they are.
        val activity = readDeviceFile(main)
        shell("am force-stop $APP")
        writeDeviceFile(toml, withDependency(declared, ROOM))
        writeDeviceFile(main, activity.trimEnd() + "\n\n" + NOTES)

        restartAndOpen(name)
        // Declaring the dependency re-resolves on open. Build only once Room is RESOLVED: a build that starts
        // first has no room-runtime on its classpath, so KSP never runs and the build "succeeds" with nothing
        // generated. (The "Resolving dependencies…" chip can't be relied on: it may not have appeared yet.)
        poll(PROJECT_TIMEOUT, 2_000) {
            shell("cat $dir/.platform/libraries.json $dir/.platform/workspace.json").takeIf { "room-runtime" in it }
        } ?: failWithScreen("room-runtime never resolved after declaring it in module.toml")
        poll(PROJECT_TIMEOUT) { findNow("Resolving dependencies…").let { if (it == null) true else null } }
        run("bundleDebug (.aab) · app")
        awaitBuildSucceeded()

        // Files the app just wrote can take a moment to show up to the shell.
        val generated = poll(30_000, 1_000) {
            shell("find $dir/app/build -name NotesDatabase_Impl*").takeIf { "NotesDatabase_Impl" in it }
        }
        if (generated == null) failWithScreen(
            "the build succeeded but Room generated no NotesDatabase_Impl; generated/: " +
                shell("find $dir/app/build/generated -type f").take(1_500),
        )
    }

    /** [toml] with [coordinate] added to [dependencies] `implementation`, creating either if missing. */
    private fun withDependency(toml: String, coordinate: String): String {
        val entry = "\"$coordinate\""
        val header = toml.indexOf("[dependencies]")
        if (header < 0) return toml.trimEnd() + "\n\n[dependencies]\nimplementation = [$entry]\n"
        val section = toml.substring(header)
        val impl = Regex("""(?m)^implementation\s*=\s*\[""").find(section)
            // stop at the next table header: an `implementation` there belongs to a variant sub-table
            ?.takeIf { m -> section.indexOf("\n[", 1).let { it < 0 || m.range.first < it } }
        return if (impl != null) {
            val at = header + impl.range.last + 1
            toml.substring(0, at) + "$entry, " + toml.substring(at)
        } else {
            val at = header + "[dependencies]".length
            toml.substring(0, at) + "\nimplementation = [$entry]" + toml.substring(at)
        }
    }

    private companion object {
        const val ROOM = "androidx.room:room-runtime:2.8.4"

        /** An entity, a DAO and a database: enough for Room to generate `NotesDatabase_Impl`. */
        val NOTES = """
            @androidx.room.Entity
            data class Note(@androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0, val text: String)

            @androidx.room.Dao
            interface NoteDao {
                @androidx.room.Query("SELECT * FROM Note ORDER BY id")
                fun all(): List<Note>

                @androidx.room.Insert
                fun add(note: Note): Long
            }

            @androidx.room.Database(entities = [Note::class], version = 1, exportSchema = false)
            abstract class NotesDatabase : androidx.room.RoomDatabase() {
                abstract fun notes(): NoteDao
            }
        """.trimIndent() + "\n"
    }
}
