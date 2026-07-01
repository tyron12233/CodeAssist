package dev.ide.lang.jdt.index

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Semantic (binding-free JDT parse) detection of runnable `main` entry points in Java source — no regex. */
class JavaMainScanTest {

    @Test fun staticMain() {
        assertEquals(listOf("p.Main" to false), JavaMainScan.scan("package p;\nclass Main { public static void main(String[] a){} }"))
    }

    @Test fun varargsStaticMain() {
        assertEquals(listOf("p.M" to false), JavaMainScan.scan("package p;\npublic class M { public static void main(String... a){} }"))
    }

    @Test fun noPackage() {
        assertEquals(listOf("App" to false), JavaMainScan.scan("public class App { public static void main(String[] a){} }"))
    }

    @Test fun instanceMainNoArgs() {
        assertEquals(listOf("p.T" to true), JavaMainScan.scan("package p;\nclass T { void main(){} }"))
    }

    @Test fun instanceMainStringArray() {
        assertEquals(listOf("p.T" to true), JavaMainScan.scan("package p;\nclass T { void main(String[] a){} }"))
    }

    @Test fun instanceMainNeedsNoArgConstructor() {
        val hits = JavaMainScan.scan("package p;\nclass T { T(int x){} void main(){} }")
        assertTrue(hits.isEmpty(), "a class with only a parameterized constructor can't be instantiated to run: $hits")
    }

    @Test fun staticPreferredOverInstance() {
        assertEquals(listOf("p.T" to false), JavaMainScan.scan("package p;\nclass T { public static void main(String[] a){} void main(){} }"))
    }

    @Test fun nonMainMethodsIgnored() {
        assertTrue(JavaMainScan.scan("package p;\nclass T { void run(){} static void helper(){} }").isEmpty())
    }

    @Test fun instanceMainWithReturnTypeIgnored() {
        // A `main` that returns non-void isn't an entry point.
        assertTrue(JavaMainScan.scan("package p;\nclass T { int main(){ return 0; } }").isEmpty())
    }
}
