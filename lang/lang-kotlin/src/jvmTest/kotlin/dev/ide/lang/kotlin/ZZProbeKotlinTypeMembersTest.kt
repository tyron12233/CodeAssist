package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.testkit.TestJars
import kotlin.test.Test

class ZZProbeKotlinTypeMembersTest {
    @Test
    fun probe() {
        val src = tempProject(mapOf("Use.kt" to "package demo\n"))
        val jars = listOfNotNull(
            stdlibJarPath().toString(),
            TestJars.onClasspath("dev/ide/lang/kotlin/symbols/KotlinType.class").toString(),
            TestJars.onClasspath("dev/ide/lang/resolve/TypeRef.class").toString(),
        )
        val svc = KotlinSymbolService(listOf(DiskFile(src)), jars)
        for (fqn in listOf("dev.ide.lang.kotlin.symbols.KotlinType", "dev.ide.lang.resolve.TypeRef")) {
            val known = svc.isKnownType(fqn)
            val mem = runCatching { svc.membersOf(fqn, emptyList(), null).map { it.name }.distinct() }
                .getOrElse { listOf("THREW:$it") }
            val named = runCatching { svc.membersNamed(fqn, emptyList(), "qualifiedName").map { it.name } }
                .getOrElse { listOf("THREW:$it") }
            println("PROBE $fqn known=$known qualifiedName=$named members=${mem.take(12)}")
            println("PROBE $fqn supertypes=${runCatching { svc.supertypesOf(fqn).map { it.qualifiedName } }.getOrElse { listOf("THREW") }}")
        }
    }
}
