package dev.ide.lang.kotlin.symbols

import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.SymbolOrigin
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * The Java bytecode shape of a classpath type (the no-`@Metadata` branch): plain Java/Android APIs
 * (`android.jar`, Java libs) carry no Kotlin metadata, so their members are read straight from bytecode
 * with ASM. This is what makes `view.findViewById`, `string` Java methods, etc. complete. No Kotlin sugar
 * (a Java `getX()` stays `getX()`, platform types are nullability-unknown).
 *
 * Bytecode is read rather than the name-keyed `java.members` index because `expr.` completion needs all
 * members of one owner type, and reading works before the index has finished building.
 */
class JavaShape(
    val superFqn: String?,
    val interfaceFqns: List<String>,
    val members: List<KotlinSymbol>,
)

object JavaBytecode {

    private val BINARY = SymbolOrigin(fromSource = false, file = null)

    fun read(bytes: ByteArray, ctx: KotlinTypeContext?): JavaShape? {
        val cr = runCatching { ClassReader(bytes) }.getOrNull() ?: return null
        val members = ArrayList<KotlinSymbol>()
        var superFqn: String? = null
        val ifaces = ArrayList<String>()
        cr.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(version: Int, access: Int, name: String?, sig: String?, superName: String?, interfaces: Array<out String>?) {
                superFqn = superName?.replace('/', '.')
                interfaces?.forEach { ifaces += it.replace('/', '.') }
            }

            override fun visitMethod(access: Int, name: String, descriptor: String, sig: String?, exceptions: Array<out String>?): MethodVisitor? {
                if (hidden(access)) return null
                if (name == "<init>" || name == "<clinit>") return null
                val t = Type.getMethodType(descriptor)
                val params = t.argumentTypes.mapIndexed { i, at -> "p$i: ${at.className.substringAfterLast('.')}" }
                members += KotlinSymbol(
                    name = name,
                    kind = SymbolKind.METHOD,
                    type = ctx?.let { KotlinType(t.returnType.className, context = it) },
                    modifiers = mods(access),
                    origin = BINARY,
                    signature = "(${params.joinToString(", ")}): ${t.returnType.className.substringAfterLast('.')}",
                )
                return null
            }

            override fun visitField(access: Int, name: String, descriptor: String, sig: String?, value: Any?): FieldVisitor? {
                if (hidden(access)) return null
                val t = Type.getType(descriptor)
                members += KotlinSymbol(
                    name = name,
                    kind = SymbolKind.FIELD,
                    type = ctx?.let { KotlinType(t.className, context = it) },
                    modifiers = mods(access),
                    origin = BINARY,
                    signature = ": ${t.className.substringAfterLast('.')}",
                )
                return null
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return JavaShape(superFqn, ifaces, members)
    }

    private fun hidden(access: Int): Boolean =
        access and (Opcodes.ACC_PRIVATE or Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE) != 0

    private fun mods(access: Int): Set<Modifier> = buildSet {
        if (access and Opcodes.ACC_PUBLIC != 0) add(Modifier.PUBLIC)
        if (access and Opcodes.ACC_PROTECTED != 0) add(Modifier.PROTECTED)
        if (access and Opcodes.ACC_STATIC != 0) add(Modifier.STATIC)
        if (access and Opcodes.ACC_FINAL != 0) add(Modifier.FINAL)
        if (access and Opcodes.ACC_ABSTRACT != 0) add(Modifier.ABSTRACT)
    }
}
