package dev.ide.android.support.aidl

import dev.ide.android.support.assumeAndroidSdk
import dev.ide.lang.jdt.compile.JdtBatchCompiler
import dev.ide.testkit.withTempDir
import dev.ide.testkit.writeSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * End-to-end cover for the AIDL compiler: a `.aidl` file in, Java out, and (the assertion that actually
 * matters) that Java compiling against a real `android.jar`. A generator can produce plausible-looking
 * source indefinitely; only running it through a compiler proves the marshalling calls exist with the
 * signatures used, which is why every shape (directions, arrays, lists, oneway, parcelables) is exercised
 * through one interface that is then compiled rather than string-matched.
 */
class AidlCompilerTest {

    /** The kitchen-sink interface: every argument direction and every supported marshalling shape. */
    @Test
    fun generatesInterfaceThatCompilesAgainstAndroidJar() {
        val sdk = assumeAndroidSdk()
        withTempDir("aidl-compile") { dir ->
            val aidl = dir.resolve("aidl")
            aidl.writeSource("com/example/Point.aidl", "package com.example;\nparcelable Point;\n")
            aidl.writeSource(
                "com/example/IRemote.aidl",
                """
                package com.example;

                import com.example.Point;

                /** A remote service. */
                interface IRemote {
                    const int VERSION = 3;
                    const String TAG = "remote";

                    /** Adds two numbers. */
                    int add(int a, int b);
                    long total(long seed, double weight, float bias, boolean flag, char tag, byte b);
                    String greet(String name);
                    CharSequence styled(CharSequence text);
                    IBinder token();
                    IRemote peer(IRemote other);

                    void move(in Point from, out Point to, inout Point cursor);
                    void fill(out int[] buffer, inout String[] names, in byte[] payload);
                    void collect(out List<Point> found, in List<String> filters, inout List<IBinder> tokens);
                    void tag(in Map values);
                    Point[] all();
                    oneway void ping(int nonce);
                    void pinned() = 42;
                }
                """.trimIndent(),
            )

            val out = dir.resolve("gen")
            val result = AidlCompiler.compile(
                AidlCompileRequest(
                    sourceRoots = listOf(aidl),
                    frameworkAidl = sdk.androidJar.resolveSibling("framework.aidl"),
                    classpath = listOf(sdk.androidJar),
                    outputDir = out,
                )
            )

            assertTrue(result.diagnostics.none { it.severity == AidlSeverity.ERROR }, "${result.diagnostics}")
            // `parcelable Point;` is a forward declaration: it names a hand-written class and emits nothing.
            assertEquals(listOf(out.resolve("com/example/IRemote.java")), result.generated)

            val source = out.resolve("com/example/IRemote.java").readText()
            assertContains(source, "public static abstract class Stub extends android.os.Binder")
            assertContains(source, "public static final java.lang.String DESCRIPTOR = \"com.example.IRemote\";")
            assertContains(source, "static final int TRANSACTION_pinned = (android.os.IBinder.FIRST_CALL_TRANSACTION + 42);")
            assertContains(source, "android.os.IBinder.FLAG_ONEWAY")
            assertContains(source, "/**")  // the doc comment survived onto the generated interface

            assertCompiles(dir, sdk.androidJar, out, handWrittenPoint(dir))
        }
    }

    /** A structured parcelable generates a real class with AIDL's length-prefixed framing. */
    @Test
    fun generatesStructuredParcelable() {
        val sdk = assumeAndroidSdk()
        withTempDir("aidl-parcelable") { dir ->
            val aidl = dir.resolve("aidl")
            aidl.writeSource(
                "com/example/Config.aidl",
                """
                package com.example;

                parcelable Config {
                    const int MAX = 10;
                    int width = 1;
                    int height = 1;
                    String label;
                    List<String> tags;
                    boolean enabled = true;
                }
                """.trimIndent(),
            )
            val out = dir.resolve("gen")
            val result = compile(aidl, out, sdk.androidJar)
            assertTrue(result.diagnostics.none { it.severity == AidlSeverity.ERROR }, "${result.diagnostics}")

            val source = out.resolve("com/example/Config.java").readText()
            assertContains(source, "public class Config implements android.os.Parcelable")
            assertContains(source, "public static final android.os.Parcelable.Creator<Config> CREATOR")
            assertContains(source, "public int width = 1;")
            // The framing is what lets a peer built against an older copy read a parcel that gained fields.
            assertContains(source, "_aidl_parcel.setDataPosition(_aidl_start_pos + _aidl_parcelable_size);")
            assertCompiles(dir, sdk.androidJar, out)
        }
    }

    /** An AIDL enum becomes an annotation type of backing-typed constants, continuing from the last assigned. */
    @Test
    fun generatesEnumConstants() {
        val sdk = assumeAndroidSdk()
        withTempDir("aidl-enum") { dir ->
            val aidl = dir.resolve("aidl")
            aidl.writeSource(
                "com/example/Level.aidl",
                """
                package com.example;

                @Backing(type="int")
                enum Level {
                    LOW,
                    MEDIUM = 5,
                    HIGH,
                }
                """.trimIndent(),
            )
            val out = dir.resolve("gen")
            val result = compile(aidl, out, sdk.androidJar)
            assertTrue(result.diagnostics.none { it.severity == AidlSeverity.ERROR }, "${result.diagnostics}")

            val source = out.resolve("com/example/Level.java").readText()
            assertContains(source, "public @interface Level")
            assertContains(source, "public static final int LOW = (int)(0);")
            assertContains(source, "public static final int MEDIUM = (int)(5);")
            assertContains(source, "public static final int HIGH = (int)(6);")
            assertCompiles(dir, sdk.androidJar, out)
        }
    }

    /** An enum-typed value is its backing primitive everywhere, since the Java backend has no Java enum. */
    @Test
    fun enumValuesMarshalAsTheirBackingType() {
        withTempDir("aidl-enum-arg") { dir ->
            val aidl = dir.resolve("aidl")
            aidl.writeSource("com/example/Level.aidl", "package com.example;\n@Backing(type=\"int\")\nenum Level { LOW, HIGH }\n")
            aidl.writeSource(
                "com/example/ILevels.aidl",
                "package com.example;\nimport com.example.Level;\ninterface ILevels { Level current(); void set(Level level); }\n",
            )
            val out = dir.resolve("gen")
            val result = compile(aidl, out, null)
            assertTrue(result.diagnostics.none { it.severity == AidlSeverity.ERROR }, "${result.diagnostics}")

            val source = out.resolve("com/example/ILevels.java").readText()
            assertContains(source, "public int current() throws android.os.RemoteException;")
            assertContains(source, "public void set(int level) throws android.os.RemoteException;")
        }
    }

    /** A `oneway` interface makes every method oneway: no reply parcel, FLAG_ONEWAY on every transaction. */
    @Test
    fun onewayInterfaceMakesEveryMethodOneway() {
        withTempDir("aidl-oneway") { dir ->
            val aidl = dir.resolve("aidl")
            aidl.writeSource("com/example/IEvents.aidl", "package com.example;\noneway interface IEvents { void a(); void b(int x); }\n")
            val out = dir.resolve("gen")
            val result = compile(aidl, out, null)
            assertTrue(result.diagnostics.none { it.severity == AidlSeverity.ERROR }, "${result.diagnostics}")

            val source = out.resolve("com/example/IEvents.java").readText()
            assertFalse(source.contains("_aidl_reply.readException()"), "a oneway call has no reply to read")
            assertEquals(2, Regex("FLAG_ONEWAY").findAll(source).count())
        }
    }

    /**
     * The `out`/`inout` protocol is an ordering contract between two generated methods, and a compiler cannot
     * see a mismatch: a stub that writes results in one order and a proxy that reads them in another
     * compiles perfectly and corrupts every call. Pin the order: return value first, then `out`/`inout`
     * parameters in declaration order, written by the stub and read by the proxy in exactly that sequence.
     */
    @Test
    fun outParametersTravelBackInDeclarationOrder() {
        withTempDir("aidl-order") { dir ->
            val aidl = dir.resolve("aidl")
            aidl.writeSource("com/example/Point.aidl", "package com.example;\nparcelable Point;\n")
            aidl.writeSource(
                "com/example/IOrder.aidl",
                """
                package com.example;
                import com.example.Point;
                interface IOrder {
                    int probe(in Point origin, out int[] sizes, inout Point cursor);
                }
                """.trimIndent(),
            )
            val out = dir.resolve("gen")
            val result = compile(aidl, out, null)
            assertTrue(result.diagnostics.none { it.severity == AidlSeverity.ERROR }, "${result.diagnostics}")
            val source = out.resolve("com/example/IOrder.java").readText()

            val stub = source.substringAfter("case TRANSACTION_probe:").substringBefore("private static class Proxy")
            assertOrder(stub, "_aidl_reply.writeNoException();", "_aidl_reply.writeInt(_aidl_ret);", "_aidl_reply.writeIntArray(_aidl_arg1);", "_aidl_arg2.writeToParcel(_aidl_reply")

            val proxy = source.substringAfter("private static class Proxy")
            assertOrder(proxy, "_aidl_reply.readException();", "_aidl_ret = _aidl_reply.readInt();", "_aidl_reply.readIntArray(sizes);", "cursor.readFromParcel(_aidl_reply);")

            // `out` sends only the array's length outbound, and nothing at all for a parcelable the callee fills.
            val request = proxy.substringAfter("writeInterfaceToken").substringBefore("mRemote.transact")
            assertContains(request, "_aidl_data.writeInt(sizes.length);")
        }
    }

    /** Assert [parts] appear in [text] in the given order, reporting the first one that is out of place. */
    private fun assertOrder(text: String, vararg parts: String) {
        var cursor = -1
        for (part in parts) {
            val at = text.indexOf(part, cursor + 1)
            assertTrue(at > cursor, "expected '$part' after offset $cursor in:\n$text")
            cursor = at
        }
    }

    // ---------------------------------------------------------------- diagnostics

    /** A parcelable parameter with no direction is the classic AIDL mistake; it has to be an error, not a guess. */
    @Test
    fun missingDirectionOnParcelableIsAnError() {
        withTempDir("aidl-direction") { dir ->
            val aidl = dir.resolve("aidl")
            aidl.writeSource("com/example/Point.aidl", "package com.example;\nparcelable Point;\n")
            aidl.writeSource(
                "com/example/IBad.aidl",
                "package com.example;\nimport com.example.Point;\ninterface IBad { void move(Point p); }\n",
            )
            val out = dir.resolve("gen")
            val result = compile(aidl, out, null)

            assertTrue(result.hasErrors)
            assertContains(result.diagnostics.first { it.severity == AidlSeverity.ERROR }.message, "must say 'in', 'out' or 'inout'")
            // Nothing is written for a declaration that failed, so the error is not buried under javac noise.
            assertFalse(Files.exists(out.resolve("com/example/IBad.java")))
        }
    }

    @Test
    fun onewayMethodCannotReturnAValue() {
        withTempDir("aidl-oneway-return") { dir ->
            val aidl = dir.resolve("aidl")
            aidl.writeSource("com/example/IBad.aidl", "package com.example;\ninterface IBad { oneway int f(); }\n")
            val result = compile(aidl, dir.resolve("gen"), null)
            assertTrue(result.hasErrors)
            assertContains(result.diagnostics.first().message, "must return void")
        }
    }

    @Test
    fun unknownTypeIsReportedWithItsName() {
        withTempDir("aidl-unknown") { dir ->
            val aidl = dir.resolve("aidl")
            aidl.writeSource("com/example/IBad.aidl", "package com.example;\ninterface IBad { void f(in Mystery m); }\n")
            val result = compile(aidl, dir.resolve("gen"), null)
            assertTrue(result.hasErrors)
            assertContains(result.diagnostics.first().message, "unknown type 'Mystery'")
        }
    }

    @Test
    fun syntaxErrorCarriesItsPosition() {
        withTempDir("aidl-syntax") { dir ->
            val aidl = dir.resolve("aidl")
            aidl.writeSource("com/example/IBad.aidl", "package com.example;\ninterface IBad {\n  int f(\n}\n")
            val result = compile(aidl, dir.resolve("gen"), null)
            assertTrue(result.hasErrors)
            val error = result.diagnostics.first { it.severity == AidlSeverity.ERROR }
            assertEquals(4, error.pos.line)
            assertContains(error.message, "expected")
        }
    }

    @Test
    fun twoMethodsCannotShareATransactionId() {
        withTempDir("aidl-clash") { dir ->
            val aidl = dir.resolve("aidl")
            aidl.writeSource("com/example/IBad.aidl", "package com.example;\ninterface IBad { void a() = 1; void b(); void c() = 1; }\n")
            val result = compile(aidl, dir.resolve("gen"), null)
            assertTrue(result.hasErrors)
            assertContains(result.diagnostics.first { it.severity == AidlSeverity.ERROR }.message, "used by more than one method")
        }
    }

    /** A package that does not match the file's location still generates, but says why an import will fail. */
    @Test
    fun packagePathMismatchWarnsWithoutFailing() {
        withTempDir("aidl-path") { dir ->
            val aidl = dir.resolve("aidl")
            aidl.writeSource("wrong/place/IThing.aidl", "package com.example;\ninterface IThing { void f(); }\n")
            val out = dir.resolve("gen")
            val result = compile(aidl, out, null)

            assertFalse(result.hasErrors)
            assertContains(result.diagnostics.single().message, "does not match the file's location")
            assertTrue(Files.exists(out.resolve("com/example/IThing.java")))
        }
    }

    /** Types declared by a dependency's aidl root resolve without that dependency's stubs being regenerated. */
    @Test
    fun importRootsContributeTypesButAreNotGenerated() {
        withTempDir("aidl-imports") { dir ->
            val lib = dir.resolve("lib-aidl")
            lib.writeSource("com/lib/Shared.aidl", "package com.lib;\nparcelable Shared;\n")
            lib.writeSource("com/lib/ILib.aidl", "package com.lib;\ninterface ILib { void f(); }\n")
            val app = dir.resolve("aidl")
            app.writeSource(
                "com/example/IApp.aidl",
                "package com.example;\nimport com.lib.Shared;\nimport com.lib.ILib;\ninterface IApp { void f(in Shared s, ILib l); }\n",
            )
            val out = dir.resolve("gen")
            val result = AidlCompiler.compile(
                AidlCompileRequest(sourceRoots = listOf(app), importRoots = listOf(lib), outputDir = out)
            )

            assertTrue(result.diagnostics.none { it.severity == AidlSeverity.ERROR }, "${result.diagnostics}")
            assertEquals(listOf(out.resolve("com/example/IApp.java")), result.generated)
            assertFalse(Files.exists(out.resolve("com/lib/ILib.java")), "an import root is a type source, not a build input")
        }
    }

    /** A removed `.aidl` must not leave its stale `.java` behind on the compile source path. */
    @Test
    fun regeneratingDropsStaleOutput() {
        withTempDir("aidl-stale") { dir ->
            val aidl = dir.resolve("aidl")
            aidl.writeSource("com/example/IGone.aidl", "package com.example;\ninterface IGone { void f(); }\n")
            aidl.writeSource("com/example/IKept.aidl", "package com.example;\ninterface IKept { void f(); }\n")
            val out = dir.resolve("gen")
            compile(aidl, out, null)
            assertTrue(Files.exists(out.resolve("com/example/IGone.java")))

            Files.delete(aidl.resolve("com/example/IGone.aidl"))
            compile(aidl, out, null)
            assertFalse(Files.exists(out.resolve("com/example/IGone.java")))
            assertTrue(Files.exists(out.resolve("com/example/IKept.java")))
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun compile(aidlRoot: Path, out: Path, androidJar: Path?) = AidlCompiler.compile(
        AidlCompileRequest(
            sourceRoots = listOf(aidlRoot),
            classpath = listOfNotNull(androidJar),
            outputDir = out,
        )
    )

    /** The hand-written `Parcelable` that `parcelable Point;` promises exists, with the `readFromParcel` an `out` needs. */
    private fun handWrittenPoint(dir: Path): Path = dir.resolve("java").also { java ->
        java.writeSource(
            "com/example/Point.java",
            """
            package com.example;

            public class Point implements android.os.Parcelable {
                public int x;
                public int y;

                public Point() {}

                public static final android.os.Parcelable.Creator<Point> CREATOR =
                    new android.os.Parcelable.Creator<Point>() {
                        public Point createFromParcel(android.os.Parcel in) {
                            Point p = new Point();
                            p.readFromParcel(in);
                            return p;
                        }
                        public Point[] newArray(int size) { return new Point[size]; }
                    };

                public void readFromParcel(android.os.Parcel in) {
                    x = in.readInt();
                    y = in.readInt();
                }

                @Override public void writeToParcel(android.os.Parcel out, int flags) {
                    out.writeInt(x);
                    out.writeInt(y);
                }

                @Override public int describeContents() { return 0; }
            }
            """.trimIndent(),
        )
    }

    /** Compile every `.java` under [roots] against `android.jar` and fail with the compiler's own message. */
    private fun assertCompiles(dir: Path, androidJar: Path, vararg roots: Path) {
        val sources = roots.flatMap { root ->
            Files.walk(root).use { s -> s.filter { it.toString().endsWith(".java") }.toList() }
        }
        assertTrue(sources.isNotEmpty(), "nothing was generated to compile")
        val result = JdtBatchCompiler.compile(
            sources = sources,
            classpath = listOf(androidJar),
            outputDir = dir.resolve("classes"),
            sourceLevel = "8",
            bootClasspath = listOf(androidJar),
        )
        val errors = result.diagnostics.filter { it.isError }
        assertTrue(result.success && errors.isEmpty(), "generated AIDL Java did not compile:\n" + errors.joinToString("\n"))
    }
}
