package dev.ide.android.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
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

    /**
     * Completion and errors in the editor come from the compiler: a member of a local variable's type, which
     * only a semantic model knows, and the error for the statement left half typed.
     */
    @Test
    fun editorCompletesAndReportsErrors() {
        assumeTrue("needs tools/dotnet-toolchain/build-dotnet-android.sh to have been run", bundled)
        val name = uniqueName("CsEdit")
        createProject("C# Console App", name)
        val program = "${projectDir(name)}/Program.cs"
        readDeviceFile(program)
        writeDeviceFile(program, "string text = \"abc\";\nConsole.WriteLine(text.Length);\n")
        restartAndOpen(name)

        openFile("Program")
        // The code area is a canvas with its own input connection (no accessibility text): focus it, go to
        // the end of the last statement, and type through injected key events.
        device.click(device.displayWidth / 2, device.displayHeight * 2 / 5)
        Thread.sleep(500)
        repeat(3) { shell("input keyevent KEYCODE_PAGE_DOWN") }
        shell("input keyevent KEYCODE_DPAD_UP")
        shell("input keyevent KEYCODE_MOVE_END")
        shell("input keyevent KEYCODE_ENTER")
        // Plain lowercase only: `input text` mangles shifted characters and brackets.
        shell("input text text.toupperi")
        // The first request starts the plugin's server and unpacks .NET, so it gets a build's patience.
        await(By.textStartsWith("ToUpperInvariant"), BUILD_TIMEOUT)
        // The half-typed call is an error, and the editor shows the compiler's own message for it.
        awaitTextContains("does not contain a definition for 'toupperi'", 60_000)
    }

    /** A package named in the project file is fetched from nuget.org, compiled against, and found at run time. */
    @Test
    fun nugetPackageIsRestoredAndUsed() {
        assumeTrue("needs tools/dotnet-toolchain/build-dotnet-android.sh to have been run", bundled)
        val name = uniqueName("CsPkg")
        createProject("C# Console App", name)
        val dir = projectDir(name)
        val project = shell("ls $dir").lines().map { it.trim() }.firstOrNull { it.endsWith(".csproj") }
            ?: error("no .csproj in $dir: ${shell("ls $dir")}")
        val declared = readDeviceFile("$dir/$project")
        check("</Project>" in declared) { "unexpected project file: $declared" }
        writeDeviceFile("$dir/$project", declared.replace("</Project>", "$PACKAGE\n</Project>"))
        readDeviceFile("$dir/Program.cs")
        writeDeviceFile("$dir/Program.cs", PACKAGE_PROGRAM)
        restartAndOpen(name)

        run("Run $name")
        awaitTextContains("Process finished with exit code", BUILD_TIMEOUT)
        awaitTextContains("json [1,2,3]")
        awaitTextContains("Process finished with exit code 0")
    }

    private companion object {
        const val PLUGIN_ID = "dev.codeassist.csharp"
        const val ASSET = "dotnet-plugin.apk"

        const val PACKAGE = """  <ItemGroup>
    <PackageReference Include="Newtonsoft.Json" Version="13.0.3" />
  </ItemGroup>"""

        val PACKAGE_PROGRAM = """
            using Newtonsoft.Json;

            class Program
            {
                static void Main(string[] args)
                {
                    Console.WriteLine("json " + JsonConvert.SerializeObject(new int[] { 1, 2, 3 }));
                }
            }
        """.trimIndent() + "\n"

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
