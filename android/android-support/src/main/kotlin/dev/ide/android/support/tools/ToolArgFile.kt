package dev.ide.android.support.tools

import java.nio.file.Files
import java.nio.file.Path

/**
 * A D8/R8/L8 `@<file>` argument file: the tool's own arguments written one per line, so the command line that
 * launches the tool stays a handful of words however many inputs a build has.
 *
 * Passing the inputs inline does not scale. A merge or a whole-program R8 run names every dex archive, jar and
 * `--classpath` entry, and each is an absolute path deep in the project's build dir, so a dependency-heavy app
 * overruns the kernel's limit on a process's argument block and the launch fails before the tool starts
 * (`error=7, Argument list too long`). The file has no such limit.
 *
 * The tools expand an `@<file>` argument by reading the file's lines, each line becoming one argument
 * verbatim, so a path containing spaces needs no quoting. A line break cannot be represented, so an argument
 * holding one is rejected rather than silently split in two. Only the tool's arguments belong in the file: the
 * VM's own options and its `-cp` are parsed by the launcher, which does not read argument files.
 */
object ToolArgFile {

    /** The launcher argument that makes the tool read its arguments from [file]. */
    fun reference(file: Path): String = "@$file"

    /**
     * Write [args] into a new file under [dir] and return it. The caller deletes it once the tool has exited;
     * [withArgFile] does both.
     */
    fun write(dir: Path, args: List<String>): Path {
        args.firstOrNull { '\n' in it || '\r' in it }?.let {
            throw IllegalArgumentException("a tool argument cannot contain a line break: '${it.replace("\n", "\\n").replace("\r", "\\r")}'")
        }
        Files.createDirectories(dir)
        val file = Files.createTempFile(dir, "tool-args", ".txt")
        Files.write(file, args)
        return file
    }

    /** Write [args] under [dir], run [body] with the `@<file>` argument, and delete the file afterwards. */
    inline fun <T> withArgFile(dir: Path, args: List<String>, body: (argument: String) -> T): T {
        val file = write(dir, args)
        return try {
            body(reference(file))
        } finally {
            runCatching { Files.deleteIfExists(file) }
        }
    }
}
