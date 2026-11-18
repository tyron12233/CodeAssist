package dev.ide.android.e2e

import android.view.KeyEvent
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
        val project = projectFileIn(dir)
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

    /**
     * A project that references another builds it first and runs against it. The second project is written
     * by a program run in the first, since only files the app created are readable to it.
     */
    @Test
    fun referencedProjectIsBuiltAndUsed() {
        assumeTrue("needs tools/dotnet-toolchain/build-dotnet-android.sh to have been run", bundled)
        val name = uniqueName("CsRef")
        createProject("C# Console App", name)
        val dir = projectDir(name)
        val project = projectFileIn(dir)
        readDeviceFile("$dir/Program.cs")
        writeDeviceFile("$dir/Program.cs", LIBRARY_WRITER)
        restartAndOpen(name)
        run("Run $name")
        awaitTextContains("library written", BUILD_TIMEOUT)
        awaitTextContains("Process finished with exit code 0")

        val declared = readDeviceFile("$dir/$project")
        check("</Project>" in declared) { "unexpected project file: $declared" }
        writeDeviceFile("$dir/$project", declared.replace("</Project>", "$REFERENCE\n</Project>"))
        writeDeviceFile("$dir/Program.cs", LIBRARY_USER)
        restartAndOpen(name)
        run("Run $name")
        awaitTextContains("library says Hello, e2e", BUILD_TIMEOUT)
        awaitTextContains("Process finished with exit code 0")
    }

    /**
     * A project that references xUnit gets a tests row in the Run picker, and a run of it fills the Tests
     * tab: the package is restored, the project compiled as a library, and each test reported.
     */
    @Test
    fun xunitTestsRunAndReport() {
        assumeTrue("needs tools/dotnet-toolchain/build-dotnet-android.sh to have been run", bundled)
        val name = uniqueName("CsTest")
        createProject("C# Console App", name)
        val dir = projectDir(name)
        val project = projectFileIn(dir)
        val declared = readDeviceFile("$dir/$project")
        check("</Project>" in declared) { "unexpected project file: $declared" }
        writeDeviceFile("$dir/$project", declared.replace("</Project>", "$XUNIT\n</Project>"))
        readDeviceFile("$dir/Program.cs")
        writeDeviceFile("$dir/Program.cs", XUNIT_TESTS)
        restartAndOpen(name)

        run("C# tests · $name")
        // The run opens on the Tests tab, which lists each test under its class.
        awaitTextContains("2 passed", BUILD_TIMEOUT)
        awaitTextContains("1 failed")
        awaitTextContains("CartTests")
        awaitTextContains("Sums(2, 3, 5)")
        awaitTextContains("Assert.Equal() Failure")
    }

    /** The editor marks a test class in the gutter, and the mark runs the class's tests. */
    @Test
    fun gutterMarkRunsATestClass() {
        assumeTrue("needs tools/dotnet-toolchain/build-dotnet-android.sh to have been run", bundled)
        val name = uniqueName("CsMark")
        createProject("C# Console App", name)
        val dir = projectDir(name)
        val project = projectFileIn(dir)
        val declared = readDeviceFile("$dir/$project")
        check("</Project>" in declared) { "unexpected project file: $declared" }
        writeDeviceFile("$dir/$project", declared.replace("</Project>", "$XUNIT\n</Project>"))
        readDeviceFile("$dir/Program.cs")
        writeDeviceFile("$dir/Program.cs", XUNIT_TESTS)
        restartAndOpen(name)

        openFile("Program")
        val mark = poll(60_000) { device.findObject(By.desc("Run all tests in CartTests")) }
            ?: failWithScreen("the test class has no run mark")
        mark.click()
        awaitTextContains("2 passed", BUILD_TIMEOUT)
        awaitTextContains("1 failed")
        awaitTextContains("CartTests")
    }

    /**
     * Reformat and Go to Declaration reach Roslyn from the editor's own shortcuts: a badly spaced file is
     * saved as Roslyn lays it out, and a type's name leads to the file that declares it.
     */
    @Test
    fun editorReformatsAndGoesToDeclaration() {
        assumeTrue("needs tools/dotnet-toolchain/build-dotnet-android.sh to have been run", bundled)
        val name = uniqueName("CsNav")
        createProject("C# Console App", name)
        val dir = projectDir(name)
        // A second file, written by a program run in the project: only files the app created are readable to it.
        readDeviceFile("$dir/Program.cs")
        writeDeviceFile("$dir/Program.cs", TYPE_WRITER)
        restartAndOpen(name)
        run("Run $name")
        awaitTextContains("type written", BUILD_TIMEOUT)
        awaitTextContains("Process finished with exit code 0")

        writeDeviceFile("$dir/Program.cs", BADLY_SPACED)
        restartAndOpen(name)
        openFile("Program")
        device.click(device.displayWidth / 2, device.displayHeight * 2 / 5)
        Thread.sleep(500)
        device.pressKeyCode(KeyEvent.KEYCODE_L, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        // The first request starts the plugin's server; then the formatted text has to reach the disk.
        val formatted = poll(BUILD_TIMEOUT, 2_000) {
            device.pressKeyCode(KeyEvent.KEYCODE_S, KeyEvent.META_CTRL_ON)
            shell("cat $dir/Program.cs").takeIf { "    static void Main()" in it }
        } ?: failWithScreen("the file was never reformatted: ${shell("cat $dir/Program.cs")}")
        check("        System.Console.WriteLine(1);" in formatted) { "not laid out as expected: $formatted" }

        // After the statement, a use of the other file's type; the caret is left at the end of its name.
        repeat(3) { shell("input keyevent KEYCODE_PAGE_DOWN") }
        repeat(3) { shell("input keyevent KEYCODE_DPAD_UP") }
        shell("input keyevent KEYCODE_MOVE_END")
        shell("input keyevent KEYCODE_ENTER")
        shell("input text greeter")
        Thread.sleep(1_500)
        shell("input keyevent KEYCODE_ESCAPE")
        device.pressKeyCode(KeyEvent.KEYCODE_B, KeyEvent.META_CTRL_ON)
        awaitTextContains("greeter.cs", 60_000)
    }

    /**
     * A module that holds a second program gets a Run row for it, which builds and runs that project from its
     * own folder. The second project is written by a program run in the first.
     */
    @Test
    fun secondProgramInAModuleHasItsOwnRunRow() {
        assumeTrue("needs tools/dotnet-toolchain/build-dotnet-android.sh to have been run", bundled)
        val name = uniqueName("CsTwo")
        createProject("C# Console App", name)
        val dir = projectDir(name)
        readDeviceFile("$dir/Program.cs")
        writeDeviceFile("$dir/Program.cs", PROGRAM_WRITER)
        restartAndOpen(name)
        run("Run $name")
        awaitTextContains("tool written", BUILD_TIMEOUT)
        awaitTextContains("Process finished with exit code 0")

        restartAndOpen(name)
        run("Run Tool")
        awaitTextContains("the tool ran in Tool", BUILD_TIMEOUT)
        awaitTextContains("Process finished with exit code 0")
    }

    /** The name of the project file in [dir]. Files the app has just created can take a moment to show to the shell. */
    private fun projectFileIn(dir: String): String =
        poll(60_000, 1_000) { shell("ls $dir").lines().map { it.trim() }.firstOrNull { it.endsWith(".csproj") } }
            ?: error("no .csproj in $dir: ${shell("ls $dir")}")

    private companion object {
        const val PLUGIN_ID = "dev.codeassist.csharp"

        /** Writes a second program, a project of its own, into `Tool/` under the project it runs in. */
        val PROGRAM_WRITER = """
            class Program
            {
                static void Main(string[] args)
                {
                    Directory.CreateDirectory("Tool");
                    File.WriteAllText("Tool/Tool.csproj",
                        "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><OutputType>Exe</OutputType>" +
                        "<TargetFramework>net10.0</TargetFramework><ImplicitUsings>enable</ImplicitUsings></PropertyGroup></Project>");
                    File.WriteAllText("Tool/Program.cs",
                        "class Tool { static void Main() { Console.WriteLine(\"the tool ran in \" + new DirectoryInfo(Directory.GetCurrentDirectory()).Name); } }");
                    Console.WriteLine("tool written");
                }
            }
        """.trimIndent() + "\n"

        /** Writes a second source file beside the program it runs in. */
        val TYPE_WRITER = """
            class Program
            {
                static void Main(string[] args)
                {
                    File.WriteAllText("greeter.cs", "public class greeter { public string hi() { return \"hi\"; } }\n");
                    Console.WriteLine("type written");
                }
            }
        """.trimIndent() + "\n"

        const val BADLY_SPACED = "class Program{\nstatic void Main( ){\nSystem.Console.WriteLine( 1 );\n}\n}\n"

        const val XUNIT = """  <ItemGroup>
    <PackageReference Include="xunit" Version="2.9.3" />
  </ItemGroup>"""

        val XUNIT_TESTS = """
            using Xunit;

            namespace Shop.Tests
            {
                public class CartTests
                {
                    [Fact]
                    public void Adds() { Assert.Equal(4, 2 + 2); }

                    [Theory]
                    [InlineData(2, 3, 5)]
                    public void Sums(int a, int b, int expected) { Assert.Equal(expected, a + b); }

                    [Fact]
                    public void IsWrong() { Assert.Equal(5, 2 + 2); }
                }
            }
        """.trimIndent() + "\n"

        const val REFERENCE = """  <ItemGroup>
    <ProjectReference Include="Lib/Lib.csproj" />
  </ItemGroup>"""

        /** Writes a class library into `Lib/` under the project it runs in. */
        val LIBRARY_WRITER = """
            class Program
            {
                static void Main(string[] args)
                {
                    Directory.CreateDirectory("Lib");
                    File.WriteAllText("Lib/Lib.csproj",
                        "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><OutputType>Library</OutputType>" +
                        "<TargetFramework>net10.0</TargetFramework></PropertyGroup></Project>");
                    File.WriteAllText("Lib/Greeter.cs",
                        "namespace Lib { public class Greeter { public string Greet(string name) { return \"Hello, \" + name; } } }");
                    Console.WriteLine("library written");
                }
            }
        """.trimIndent() + "\n"

        val LIBRARY_USER = """
            using Lib;

            class Program
            {
                static void Main(string[] args)
                {
                    Console.WriteLine("library says " + new Greeter().Greet("e2e"));
                }
            }
        """.trimIndent() + "\n"
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
