package dev.ide.preview.layoutlib

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.ResourceReference
import com.android.ide.common.rendering.api.StyleableResourceValue
import com.android.resources.ResourceType
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * The integer ids of a project's resources, and the `R` classes that carry them.
 *
 * On a device aapt2 numbers the resources and writes `R`; library code then reads `R.attr.colorPrimary`,
 * `R.styleable.MaterialButton` and hands those integers back to the framework. Here nothing is compiled, so
 * the numbers are given out by this table: one id per (type, name), the same whichever package's `R` names it,
 * and the same one layoutlib is told when it asks what an id means or what id a resource has.
 *
 * `R` classes are generated when a class loader asks for one ([classBytes]), from the resource table itself, so
 * every package's `R` holds every resource. A real library `R` holds only what that library can see; the extra
 * fields are never read.
 */
internal class ResourceIds(
    private val table: ResourceTable,
    packages: Collection<String>,
    /** The framework's own id for an `android:` attribute, which a styleable array must hold as it is. */
    private val frameworkAttrId: (String) -> Int,
    /**
     * The ids the project's last build gave its resources (aapt2's `R.txt`: `int string app_name 0x7f0e0001`),
     * when it has been built. The project's own classes were compiled with those numbers in them, as constants,
     * so `stringResource(R.string.app_name)` asks for 0x7f0e0001 whatever this table would have called it:
     * they are taken as they are, and only what the build never saw is numbered here.
     */
    built: java.io.File? = null,
) {
    private val packages = packages.toSet()
    private val ids = HashMap<String, Int>()
    private val references = HashMap<Int, ResourceReference>()
    private val typeIndex = HashMap<ResourceType, Int>()
    private val nextInType = HashMap<ResourceType, Int>()

    @Synchronized
    fun idOf(type: ResourceType, name: String): Int {
        val key = type.getName() + "/" + fieldName(name)
        ids[key]?.let { return it }
        val index = typeIndex.getOrPut(type) { (typeIndex.values.maxOrNull() ?: 0) + 1 }
        val n = (nextInType[type] ?: 0) + 1
        nextInType[type] = n
        val id = 0x7f000000 or (index shl 16) or n
        ids[key] = id
        // The name as the resources spell it (`Theme.Material3.Light`), which is what an id has to resolve to.
        val real = table[type]?.get(name)?.name ?: realNames[key] ?: name
        references[id] = ResourceReference(ResourceNamespace.RES_AUTO, type, real)
        return id
    }

    @Synchronized
    fun referenceOf(id: Int): ResourceReference? = references[id]

    /** `type/field_name` -> the resource's own name, for the names a field cannot spell (dots, dashes). */
    private val realNames: Map<String, String> = buildMap {
        for ((type, values) in table) for (value in values.values()) {
            val field = fieldName(value.name)
            if (field != value.name) put(type.getName() + "/" + field, value.name)
        }
    }

    init {
        // After [realNames]: a built id resolves to the resource as the resources spell its name.
        runCatching {
            built?.takeIf { it.isFile }?.forEachLine { line ->
                val parts = line.split(' ')
                if (parts.size != 4 || parts[0] != "int") return@forEachLine
                val type = ResourceType.fromClassName(parts[1]) ?: return@forEachLine
                if (type == ResourceType.STYLEABLE) return@forEachLine
                val id = parts[3].removePrefix("0x").toLongOrNull(16)?.toInt() ?: return@forEachLine
                if (id ushr 24 != 0x7f) return@forEachLine
                val key = type.getName() + "/" + parts[2]
                ids[key] = id
                references[id] = ResourceReference(ResourceNamespace.RES_AUTO, type, table[type]?.get(parts[2])?.name ?: realNames[key] ?: parts[2])
                typeIndex[type] = (id ushr 16) and 0xff
                nextInType[type] = maxOf(nextInType[type] ?: 0, id and 0xffff)
            }
        }
    }

    /** The bytes of `pkg.R` or `pkg.R$type` for one of this project's packages, or null for any other class. */
    fun classBytes(className: String): ByteArray? {
        val dollar = className.lastIndexOf("\$")
        val outer = if (dollar < 0) className else className.substring(0, dollar)
        if (!outer.endsWith(".R") || outer.removeSuffix(".R") !in packages) return null
        val internal = className.replace('.', '/')
        if (dollar < 0) {
            val cw = ClassWriter(0)
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, internal, null, "java/lang/Object", null)
            cw.visitEnd()
            return cw.toByteArray()
        }
        val type = ResourceType.fromClassName(className.substring(dollar + 1)) ?: return null
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, internal, null, "java/lang/Object", null)
        if (type == ResourceType.STYLEABLE) styleables(cw, internal) else {
            val seen = HashSet<String>()
            for (value in table[type]?.values().orEmpty()) {
                val field = fieldName(value.name)
                if (!seen.add(field)) continue
                cw.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, field, "I", null, idOf(type, value.name)).visitEnd()
            }
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    /**
     * `int[] Name` per styleable and `int Name_attr` per attribute index. The arrays are filled by helper
     * methods in batches and are not final: one initializer for a Material app's styleables is past the 64 KB
     * a method may be.
     */
    private fun styleables(cw: ClassWriter, internal: String) {
        val helpers = ArrayList<String>()
        var mv: MethodVisitor? = null
        var inBatch = 0
        val seen = HashSet<String>()
        for (value in table[ResourceType.STYLEABLE]?.values().orEmpty()) {
            val styleable = value as? StyleableResourceValue ?: continue
            val name = fieldName(styleable.name)
            if (!seen.add(name)) continue
            cw.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, name, "[I", null, null).visitEnd()
            if (mv == null || inBatch >= BATCH) {
                mv?.finish()
                val helper = "init" + helpers.size
                helpers += helper
                mv = cw.visitMethod(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, helper, "()V", null, null).also { it.visitCode() }
                inBatch = 0
            }
            inBatch++
            val attrs = styleable.allAttributes
            mv!!.visitLdcInsn(attrs.size)
            mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT)
            attrs.forEachIndexed { index, attr ->
                val framework = attr.namespace == ResourceNamespace.ANDROID
                val id = if (framework) frameworkAttrId(attr.name) else idOf(ResourceType.ATTR, attr.name)
                mv.visitInsn(Opcodes.DUP)
                mv.visitLdcInsn(index)
                mv.visitLdcInsn(id)
                mv.visitInsn(Opcodes.IASTORE)
                val child = name + "_" + (if (framework) "android_" else "") + fieldName(attr.name)
                if (seen.add(child)) {
                    cw.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, child, "I", null, index).visitEnd()
                }
            }
            mv.visitFieldInsn(Opcodes.PUTSTATIC, internal, name, "[I")
        }
        mv?.finish()
        val init = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
        init.visitCode()
        for (helper in helpers) init.visitMethodInsn(Opcodes.INVOKESTATIC, internal, helper, "()V", false)
        init.finish()
    }

    private fun MethodVisitor.finish() {
        visitInsn(Opcodes.RETURN)
        visitMaxs(0, 0)
        visitEnd()
    }

    companion object {
        private const val BATCH = 40

        /** A resource name as an `R` field spells it. */
        fun fieldName(name: String): String = name.replace('.', '_').replace('-', '_').replace(':', '_')
    }
}
