package dev.codeassist.csharp.build.tests

import android.util.Log
import dev.codeassist.csharp.sdk.CsCompilerHost
import dev.codeassist.csharp.sdk.DotnetSdk
import dev.codeassist.csharp.sdk.ensureRuntimeConfig
import dev.codeassist.csharp.util.TAG
import dev.codeassist.csharp.util.ToolCancellation
import java.nio.file.Files
import java.nio.file.Path

/**
 * The program that runs a project's tests: `TestRunner.dll <test assembly> <kind> [targets...]`.
 *
 * It is not xUnit's, NUnit's or MSTest's own runner. Each of those is a host of its own with discovery,
 * fixtures and reporters, and none is in the packages a test project references. This one loads the test
 * assembly, finds the methods those frameworks mark as tests by the names of their attributes, and calls
 * them, which is what the IDE's own JUnit runner does for the same reason. The assertions are the
 * framework's: a failed one throws, and the exception is the failure.
 *
 * What it runs: xUnit `[Fact]` and `[Theory]` with `[InlineData]`; NUnit `[Test]` and `[TestCase]` with
 * `[SetUp]`, `[TearDown]`, `[OneTimeSetUp]` and `[OneTimeTearDown]`; MSTest `[TestMethod]` with `[DataRow]`,
 * `[TestInitialize]` and `[TestCleanup]`; async tests; a skip or ignore reason; `IDisposable` and
 * `IAsyncLifetime` on the test class. What it reports as skipped, saying why: a test class that needs
 * constructor arguments (a fixture, `ITestOutputHelper`), and a theory whose data comes from a member or a
 * class.
 *
 * A suite is the test class's full name. A target is a suite, `suite#Method` for every case of a method, or
 * `suite#Method(arguments)` for one case, which is what a re-run from the Tests tab sends back.
 */
internal object CsTestRunner {

    /** Builds the runner when its source changed or its DLL is missing, and returns the DLL. */
    @Synchronized
    fun ensure(sdk: DotnetSdk, cancellation: ToolCancellation): Path? {
        val dir = sdk.home.resolve("tools")
        val source = dir.resolve("TestRunner.cs")
        val dll = dir.resolve("TestRunner.dll")

        val current = runCatching { String(Files.readAllBytes(source), Charsets.UTF_8) }.getOrNull()
        if (current == RUNNER_SOURCE && Files.isRegularFile(dll)) {
            ensureRuntimeConfig(dir.resolve("TestRunner.runtimeconfig.json"))
            return dll
        }

        Files.createDirectories(dir)
        Files.write(source, RUNNER_SOURCE.toByteArray(Charsets.UTF_8))
        ensureRuntimeConfig(dir.resolve("TestRunner.runtimeconfig.json"))
        Files.deleteIfExists(dll)

        val arguments = buildList {
            add("-nologo")
            add("-noconfig")
            add("-target:exe")
            add("-out:$dll")
            sdk.references().forEach { add("-r:$it") }
            add(source.toString())
        }

        val code = CsCompilerHost.compile(sdk, arguments, cancellation) { Log.d(TAG, "test runner> $it") }
        if (code != 0 || !Files.isRegularFile(dll)) {
            Log.d(TAG, "tests: the runner did not build (exit $code)")
            return null
        }
        return dll
    }
}

/** The runner. No `$` anywhere in it: this is a Kotlin raw string. */
internal const val RUNNER_SOURCE = """using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Runtime.Loader;
using System.Text;
using System.Threading.Tasks;

sealed class Case
{
    public Type Type;
    public MethodInfo Method;
    public object[] Arguments;
    public string Suite;
    public string Name;
    public string Skip;
}

static class TestRunner
{
    const string Mark = "\u0001ca-test\u0002";

    static readonly string[] TestAttributes = { "FactAttribute", "TheoryAttribute", "TestAttribute", "TestCaseAttribute", "TestMethodAttribute", "DataTestMethodAttribute" };
    static readonly string[] RowAttributes = { "InlineDataAttribute", "TestCaseAttribute", "DataRowAttribute" };
    static readonly string[] DataAttributes = { "MemberDataAttribute", "ClassDataAttribute", "TestCaseSourceAttribute", "DynamicDataAttribute" };

    static int Main(string[] args)
    {
        if (args.Length < 2) return 2;
        string path = Path.GetFullPath(args[0]);
        string directory = Path.GetDirectoryName(path);
        AssemblyLoadContext.Default.Resolving += (context, name) =>
        {
            string candidate = Path.Combine(directory, name.Name + ".dll");
            return File.Exists(candidate) ? context.LoadFromAssemblyPath(candidate) : null;
        };

        Record("RUN", "", "", 0, args[1]);
        var targets = args.Skip(2).ToList();
        List<Case> cases;
        try
        {
            Assembly assembly = AssemblyLoadContext.Default.LoadFromAssemblyPath(path);
            cases = Discover(assembly).Where(c => Selected(c, targets)).ToList();
        }
        catch (Exception e)
        {
            Record("CLASS_FAILED", Path.GetFileNameWithoutExtension(path), "", 0, Describe(e));
            Record("FINISHED", "", "", 0, "");
            return 1;
        }

        foreach (Case c in cases) Record("QUEUED", c.Suite, c.Name, 0, "");
        int failed = 0;
        foreach (var group in cases.GroupBy(c => c.Type))
            failed += RunClass(group.Key, group.ToList());
        Record("FINISHED", "", "", 0, cases.Count == 0 ? "no tests" : "");
        return failed > 0 ? 1 : 0;
    }

    // ---- discovery

    static IEnumerable<Case> Discover(Assembly assembly)
    {
        Type[] types;
        try { types = assembly.GetTypes(); }
        catch (ReflectionTypeLoadException e) { types = e.Types.Where(t => t != null).ToArray(); }

        foreach (Type type in types.OrderBy(t => t.FullName, StringComparer.Ordinal))
        {
            if (!type.IsClass || (type.IsAbstract && !type.IsSealed) || type.IsGenericTypeDefinition) continue;
            string suite = (type.FullName ?? type.Name).Replace('+', '.');
            string classSkip = SkipReason(type.GetCustomAttributesData());
            var flags = BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance | BindingFlags.Static;
            foreach (MethodInfo method in type.GetMethods(flags).OrderBy(m => m.MetadataToken))
            {
                IList<CustomAttributeData> attributes;
                try { attributes = method.GetCustomAttributesData(); }
                catch (Exception) { continue; }
                if (!attributes.Any(a => TestAttributes.Contains(a.AttributeType.Name))) continue;

                string skip = classSkip ?? SkipReason(attributes);
                var rows = attributes.Where(a => RowAttributes.Contains(a.AttributeType.Name)).ToList();
                if (rows.Count == 0)
                {
                    bool needsData = method.GetParameters().Length > 0;
                    if (needsData && skip == null)
                    {
                        skip = attributes.Any(a => DataAttributes.Contains(a.AttributeType.Name))
                            ? "Its data comes from a member or a class, which this runner does not read."
                            : "It takes arguments and no data is given for them.";
                    }
                    yield return new Case { Type = type, Method = method, Arguments = new object[0], Suite = suite, Name = method.Name, Skip = skip };
                    continue;
                }
                foreach (CustomAttributeData row in rows)
                {
                    object[] values = Values(row.ConstructorArguments);
                    yield return new Case
                    {
                        Type = type, Method = method, Arguments = values, Suite = suite,
                        Name = method.Name + "(" + string.Join(", ", values.Select(Show)) + ")", Skip = skip,
                    };
                }
            }
        }
    }

    // The values of a data row. A `params object[]` arrives as one array argument, which is spread out.
    static object[] Values(IList<CustomAttributeTypedArgument> arguments)
    {
        var values = new List<object>();
        foreach (CustomAttributeTypedArgument argument in arguments)
        {
            var many = argument.Value as System.Collections.IEnumerable;
            if (many == null || argument.Value is string) { values.Add(Plain(argument.Value)); continue; }
            foreach (object item in many) values.Add(Plain(item));
        }
        return values.ToArray();
    }

    // An element of an attribute's array argument arrives wrapped with its type; the value is inside.
    static object Plain(object value)
    {
        return value is CustomAttributeTypedArgument ? ((CustomAttributeTypedArgument)value).Value : value;
    }

    static string Show(object value)
    {
        if (value == null) return "null";
        string text = value as string;
        if (text != null) return "\"" + text + "\"";
        if (value is bool) return (bool)value ? "true" : "false";
        return System.Convert.ToString(value, System.Globalization.CultureInfo.InvariantCulture);
    }

    static string SkipReason(IList<CustomAttributeData> attributes)
    {
        foreach (CustomAttributeData attribute in attributes)
        {
            string name = attribute.AttributeType.Name;
            if (name == "IgnoreAttribute")
            {
                object reason = attribute.ConstructorArguments.Count > 0 ? attribute.ConstructorArguments[0].Value : null;
                return reason as string ?? "Ignored.";
            }
            if (!TestAttributes.Contains(name)) continue;
            foreach (CustomAttributeNamedArgument named in attribute.NamedArguments)
            {
                if ((named.MemberName == "Skip" || named.MemberName == "Ignore" || named.MemberName == "IgnoreReason")
                    && named.TypedValue.Value is string) return (string)named.TypedValue.Value;
            }
        }
        return null;
    }

    static bool Selected(Case c, List<string> targets)
    {
        if (targets.Count == 0) return true;
        foreach (string target in targets)
        {
            if (target == c.Suite || target == c.Suite + "#" + c.Name || target == c.Suite + "#" + c.Method.Name) return true;
        }
        return false;
    }

    // ---- running

    static int RunClass(Type type, List<Case> cases)
    {
        string suite = cases[0].Suite;
        // NUnit runs every test of a fixture on one object; the others make a new one for each test.
        bool shared = Has(type, "TestFixtureAttribute") || Marked(type, "OneTimeSetUpAttribute").Count > 0
            || cases.Any(c => c.Method.GetCustomAttributesData().Any(a => a.AttributeType.Namespace == "NUnit.Framework"));
        object fixture = null;
        if (shared && cases.Any(c => c.Skip == null))
        {
            try
            {
                fixture = Create(type);
                foreach (MethodInfo m in Marked(type, "OneTimeSetUpAttribute")) Call(m, fixture, null);
            }
            catch (Exception e)
            {
                Record("CLASS_FAILED", suite, "", 0, Describe(Unwrap(e)));
                foreach (Case c in cases) Record("SKIPPED", c.Suite, c.Name, 0, "The fixture could not be set up.");
                return 1;
            }
        }

        int failed = 0;
        foreach (Case c in cases)
        {
            if (c.Skip != null) { Record("SKIPPED", c.Suite, c.Name, 0, c.Skip); continue; }
            Record("STARTED", c.Suite, c.Name, 0, "");
            IDisposable isolated = Isolate();
            var watch = Stopwatch.StartNew();
            string skipped = null;
            Exception failure = null;
            object instance = fixture;
            try
            {
                if (instance == null && !c.Method.IsStatic)
                {
                    if (!HasPlainConstructor(type))
                    {
                        skipped = "Its class needs constructor arguments (a fixture or an output helper), which this runner does not supply.";
                    }
                    else instance = Create(type);
                }
                if (skipped == null)
                {
                    if (instance != null) Lifetime(instance, "InitializeAsync");
                    foreach (MethodInfo m in Marked(type, "SetUpAttribute", "TestInitializeAttribute")) Call(m, instance, null);
                    Type expected = Expected(c.Method);
                    try
                    {
                        Call(c.Method, c.Method.IsStatic ? null : instance, ConvertRow(c.Method, c.Arguments));
                        if (expected != null) failure = new Exception("Expected " + expected.Name + " to be thrown, and nothing was.");
                    }
                    catch (Exception e)
                    {
                        Exception thrown = Unwrap(e);
                        if (expected == null || !expected.IsInstanceOfType(thrown)) throw;
                    }
                }
            }
            catch (Exception e)
            {
                Exception thrown = Unwrap(e);
                string kind = thrown.GetType().Name;
                if (kind == "IgnoreException" || kind == "InconclusiveException" || kind == "SkipException" || kind == "AssertInconclusiveException")
                    skipped = thrown.Message;
                else if (kind != "SuccessException") failure = thrown;
            }
            finally
            {
                try
                {
                    if (instance != null)
                    {
                        foreach (MethodInfo m in Marked(type, "TearDownAttribute", "TestCleanupAttribute")) Call(m, instance, null);
                        if (!shared)
                        {
                            Lifetime(instance, "DisposeAsync");
                            IDisposable disposable = instance as IDisposable;
                            if (disposable != null) disposable.Dispose();
                        }
                    }
                }
                catch (Exception e) { if (failure == null) failure = Unwrap(e); }
                if (isolated != null) isolated.Dispose();
            }
            watch.Stop();
            if (skipped != null) Record("SKIPPED", c.Suite, c.Name, 0, skipped);
            else if (failure != null) { failed++; Record("FAILED", c.Suite, c.Name, watch.ElapsedMilliseconds, Describe(failure)); }
            else Record("PASSED", c.Suite, c.Name, watch.ElapsedMilliseconds, "");
        }

        if (fixture != null)
        {
            try
            {
                foreach (MethodInfo m in Marked(type, "OneTimeTearDownAttribute")) Call(m, fixture, null);
                IDisposable disposable = fixture as IDisposable;
                if (disposable != null) disposable.Dispose();
            }
            catch (Exception e) { failed++; Record("CLASS_FAILED", suite, "", 0, Describe(Unwrap(e))); }
        }
        return failed;
    }

    // NUnit keeps the failures of the test in progress in a context of its own, and outside its runner that
    // context is never reset: a later test would be handed an earlier one's failure. Its own isolated context,
    // one per test, keeps them apart. Null when the tests are not NUnit's.
    static IDisposable Isolate()
    {
        try
        {
            foreach (Assembly loaded in AppDomain.CurrentDomain.GetAssemblies())
            {
                if (loaded.GetName().Name != "nunit.framework") continue;
                Type context = loaded.GetType("NUnit.Framework.Internal.TestExecutionContext+IsolatedContext");
                return context == null ? null : Activator.CreateInstance(context) as IDisposable;
            }
        }
        catch (Exception) { }
        return null;
    }

    static bool HasPlainConstructor(Type type)
    {
        return type.GetConstructor(BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance, null, Type.EmptyTypes, null) != null;
    }

    static object Create(Type type)
    {
        return Activator.CreateInstance(type, true);
    }

    static bool Has(MemberInfo member, string attribute)
    {
        return member.GetCustomAttributesData().Any(a => a.AttributeType.Name == attribute);
    }

    static List<MethodInfo> Marked(Type type, params string[] attributes)
    {
        var flags = BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance | BindingFlags.Static;
        return type.GetMethods(flags)
            .Where(m => m.GetParameters().Length == 0 && m.GetCustomAttributesData().Any(a => attributes.Contains(a.AttributeType.Name)))
            .ToList();
    }

    // MSTest's [ExpectedException(typeof(X))]: the test passes when X is thrown.
    static Type Expected(MethodInfo method)
    {
        foreach (CustomAttributeData attribute in method.GetCustomAttributesData())
        {
            if (attribute.AttributeType.Name != "ExpectedExceptionAttribute" || attribute.ConstructorArguments.Count == 0) continue;
            return attribute.ConstructorArguments[0].Value as Type;
        }
        return null;
    }

    // Calls a method and, when it returns a task, waits for it.
    static void Call(MethodInfo method, object instance, object[] arguments)
    {
        object result = method.Invoke(method.IsStatic ? null : instance, arguments);
        if (result == null) return;
        Task task = result as Task;
        if (task == null && result.GetType().Name.StartsWith("ValueTask", StringComparison.Ordinal))
        {
            MethodInfo asTask = result.GetType().GetMethod("AsTask", Type.EmptyTypes);
            if (asTask != null) task = asTask.Invoke(result, null) as Task;
        }
        if (task != null) task.GetAwaiter().GetResult();
    }

    // xUnit's IAsyncLifetime, found by the method's name so that no xUnit type is needed here.
    static void Lifetime(object instance, string method)
    {
        foreach (Type contract in instance.GetType().GetInterfaces())
        {
            if (contract.Name != "IAsyncLifetime" && !(method == "DisposeAsync" && contract.Name == "IAsyncDisposable")) continue;
            MethodInfo found = contract.GetMethod(method, Type.EmptyTypes);
            if (found != null) { Call(found, instance, null); return; }
        }
    }

    // A row's values as the method's parameter types: an int literal for a long parameter, a name for an enum.
    static object[] ConvertRow(MethodInfo method, object[] values)
    {
        ParameterInfo[] parameters = method.GetParameters();
        if (parameters.Length != values.Length)
            throw new ArgumentException("The test takes " + parameters.Length + " argument(s) and the data row has " + values.Length + ".");
        var converted = new object[values.Length];
        for (int i = 0; i < values.Length; i++)
        {
            object value = values[i];
            Type wanted = Nullable.GetUnderlyingType(parameters[i].ParameterType) ?? parameters[i].ParameterType;
            if (value == null || wanted.IsInstanceOfType(value)) converted[i] = value;
            else if (wanted.IsEnum) converted[i] = Enum.ToObject(wanted, value);
            else converted[i] = System.Convert.ChangeType(value, wanted, System.Globalization.CultureInfo.InvariantCulture);
        }
        return converted;
    }

    static Exception Unwrap(Exception e)
    {
        while ((e is TargetInvocationException || e is AggregateException) && e.InnerException != null) e = e.InnerException;
        return e;
    }

    static string Describe(Exception e)
    {
        string trace = e.StackTrace ?? "";
        // The runner's own frames say nothing about the test.
        var kept = trace.Split('\n').Where(line => !line.Contains("TestRunner.") && !line.Contains("System.Reflection.") && !line.Contains("System.RuntimeMethodHandle") && !line.Contains("InvokeStub_")).ToArray();
        string text = e.GetType().Name + ": " + e.Message;
        return kept.Length == 0 ? text : text + "\n" + string.Join("\n", kept).TrimEnd();
    }

    static void Record(string what, string suite, string test, long millis, string message)
    {
        Console.Out.Write(Mark + what + " " + suite.Length + ":" + suite + " " + test.Length + ":" + test + " " + millis + " " + message.Length + ":" + message + "\n");
        Console.Out.Flush();
    }
}
"""
