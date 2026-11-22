package dev.ide.kotlin.classfile

import java.io.File
import java.util.jar.JarFile
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmEffectExpression
import kotlin.metadata.KmEffectInvocationKind
import kotlin.metadata.KmEffectType
import kotlin.metadata.KmFunction
import kotlin.metadata.isNegated
import kotlin.metadata.isNullCheckPredicate
import kotlin.metadata.isNullable
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `contract { }` decoder against kotlin-metadata-jvm, over every function in kotlin-stdlib and
 * kotlin-test that declares one (`require`, `checkNotNull`, `isNullOrEmpty`, `assertNotNull`, `assertIs`,
 * `run`, `let`, ...). Both sides are rendered in [KotlinContractCodec]'s text form, ours through the codec
 * itself, so the round trip is checked too.
 */
@OptIn(kotlin.contracts.ExperimentalContracts::class)
class ContractOracleTest {

    @Test
    fun contractsDecodeTheSameWayTheJvmLibraryDoes() {
        val jars = System.getProperty("java.class.path").orEmpty().split(File.pathSeparator).map(::File)
            .filter { (it.name.startsWith("kotlin-stdlib-") || it.name.startsWith("kotlin-test-")) && it.name.endsWith(".jar") }
        assertTrue(jars.isNotEmpty(), "kotlin-stdlib is on the test classpath")
        var compared = 0
        val seen = HashSet<String>()
        for (jar in jars) JarFile(jar).use { zip ->
            for (entry in zip.entries().asSequence().filter { it.name.endsWith(".class") }) {
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val annotation = ClassFile.read(bytes)?.metadata ?: continue
                val theirs = runCatching {
                    KotlinClassMetadata.readStrict(
                        kotlin.Metadata(
                            kind = annotation.kind,
                            metadataVersion = annotation.metadataVersion,
                            data1 = annotation.data1,
                            data2 = annotation.data2,
                        ),
                    )
                }.getOrNull() ?: continue
                val (functions, outer) = when (theirs) {
                    is KotlinClassMetadata.Class -> theirs.kmClass.functions to theirs.kmClass.typeParameters.associate { it.id to it.name }
                    is KotlinClassMetadata.FileFacade -> theirs.kmPackage.functions to emptyMap()
                    is KotlinClassMetadata.MultiFileClassPart -> theirs.kmPackage.functions to emptyMap()
                    else -> continue
                }
                val ours = KotlinMetadata.read(annotation)?.declarations
                    ?.filter { it.kind == KotlinDeclaration.Kind.FUNCTION }.orEmpty()
                for ((i, km) in functions.withIndex()) {
                    val contract = km.contract ?: continue
                    val scope = outer + km.typeParameters.associate { it.id to it.name }
                    val expected = contract.effects.mapNotNull { effect(it, scope) }.joinToString(";")
                    val mine = ours.getOrNull(i)
                    assertEquals(km.name, mine?.name, "function order in ${entry.name}")
                    val actual = KotlinContractCodec.encode(KotlinContractCodec.decode(KotlinContractCodec.encode(mine!!.contract)))
                    assertEquals(expected, actual, "contract of ${entry.name} ${km.name}")
                    if (expected.isNotEmpty()) seen += km.name
                    compared++
                }
            }
        }
        for (name in listOf("require", "checkNotNull", "isNullOrEmpty", "assertNotNull", "assertIs", "run")) {
            assertTrue(name in seen, "a contract on $name was compared")
        }
        println("contracts: $compared functions agree with kotlin-metadata-jvm")
    }

    private fun effect(e: kotlin.metadata.KmEffect, scope: Map<Int, String>): String? {
        val conclusion = e.conclusion?.let { expression(it, scope) ?: return null }.orEmpty()
        return when (e.type) {
            KmEffectType.RETURNS_CONSTANT -> {
                val value = when (e.constructorArguments.size) {
                    0 -> ""
                    1 -> when (e.constructorArguments[0].constantValue?.value) {
                        true -> "t"
                        false -> "f"
                        null -> if (e.constructorArguments[0].constantValue != null) "n" else return null
                        else -> return null
                    }
                    else -> return null
                }
                "R$value>$conclusion"
            }
            KmEffectType.RETURNS_NOT_NULL -> "N>$conclusion"
            KmEffectType.CALLS -> {
                val p = e.constructorArguments.singleOrNull()?.parameterIndex ?: return null
                "C$p" + when (e.invocationKind) {
                    KmEffectInvocationKind.AT_MOST_ONCE -> "m"
                    KmEffectInvocationKind.EXACTLY_ONCE -> "e"
                    KmEffectInvocationKind.AT_LEAST_ONCE -> "l"
                    null -> "u"
                }
            }
            // Newer than the decoder (`returnsResultOf`): both sides leave it out.
            else -> null
        }
    }

    private fun expression(x: KmEffectExpression, scope: Map<Int, String>): String? {
        val neg = if (x.isNegated) "!" else ""
        val p = x.parameterIndex
        val type = x.isInstanceType
        val primitive = when {
            p != null && type != null -> {
                val name = when (val c = type.classifier) {
                    is KmClassifier.Class -> c.name.replace('/', '.')
                    is KmClassifier.TypeAlias -> c.name.replace('/', '.')
                    is KmClassifier.TypeParameter -> "@" + (scope[c.id] ?: return null)
                }
                "${neg}i$p:$name" + if (type.isNullable) "?" else ""
            }
            p != null && x.isNullCheckPredicate -> "${neg}z$p"
            p != null -> "${neg}p$p"
            x.constantValue != null -> "k" + when (x.constantValue!!.value) { true -> "t"; false -> "f"; else -> "n" }
            else -> null
        }
        if (x.andArguments.isNotEmpty() && x.orArguments.isNotEmpty()) return null
        val operands = x.andArguments.ifEmpty { x.orArguments }
        if (operands.isEmpty()) return primitive
        val all = listOfNotNull(primitive) + operands.map { expression(it, scope) ?: return null }
        return all.joinToString(",", if (x.andArguments.isNotEmpty()) "&(" else "|(", ")")
    }
}
