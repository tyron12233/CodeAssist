package dev.ide.android.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The C# plugin (:samples:dotnet-plugin, bundled into this test APK when its .NET toolchain has been built)
 * compiles and runs a program inside the IDE: the plugin's process launches, the runtime unpacked into the
 * IDE's storage, Roslyn, and the Run console, none of which a unit test or a shell on the device reaches.
 */
@RunWith(AndroidJUnit4::class)
class CsPluginTest : E2eTest() {

    private val bundled: Boolean by lazy { ASSET in instrumentation.context.assets.list("").orEmpty() }

    // Pre-consent so the plugin loads at startup without the review + restart round trip.
    override fun seed() = DEFAULT_SEED + mapOf("plugins.consented" to PLUGIN_ID)

    override fun beforeLaunch() {
        if (!bundled) return
        val staged = File(instrumentation.context.externalMediaDirs.first().apply { mkdirs() }, ASSET)
        instrumentation.context.assets.open(ASSET).use { input -> staged.outputStream().use(input::copyTo) }
        shell("cp ${staged.absolutePath} /data/local/tmp/$ASSET")
        val result = shell("pm install -r -t /data/local/tmp/$ASSET")
        check("Success" in result) { "installing the C# plugin failed: $result" }
    }

    @After
    fun uninstallPlugin() {
        shell("pm uninstall $PLUGIN_ID")
    }

    @Test
    fun consoleAppCompilesAndRuns() {
        assumeTrue("needs tools/dotnet-toolchain/build-dotnet-android.sh to have been run", bundled)
        val name = uniqueName("CsCon")
        createProject("C# Console App", name)

        // The template's program waits for a name on stdin. This one needs no input, and reaches past the
        // compiler: LINQ from the shared framework and SHA-256 through the OpenSSL the plugin carries.
        val program = "${projectDir(name)}/Program.cs"
        readDeviceFile(program)
        writeDeviceFile(program, PROGRAM)
        restartAndOpen(name)

        run("Run $name")
        awaitTextContains("Process finished with exit code", BUILD_TIMEOUT)
        awaitTextContains("squares 1,4,9,16,25")
        awaitTextContains("sha BA7816BF8F01CFEA")
        awaitTextContains("Process finished with exit code 0")
    }

    private companion object {
        const val PLUGIN_ID = "dev.codeassist.csharp"
        const val ASSET = "dotnet-plugin.apk"

        val PROGRAM = """
            using System.Linq;
            using System.Security.Cryptography;
            using System.Text;

            class Program
            {
                static void Main(string[] args)
                {
                    Console.WriteLine("squares " + string.Join(",", Enumerable.Range(1, 5).Select(i => i * i)));
                    string hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes("abc")));
                    Console.WriteLine("sha " + hash.Substring(0, 16));
                }
            }
        """.trimIndent() + "\n"
    }
}
