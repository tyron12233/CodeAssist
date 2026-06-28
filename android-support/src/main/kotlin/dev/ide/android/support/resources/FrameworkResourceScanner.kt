package dev.ide.android.support.resources

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Extracts the public Android framework resource names from `android.jar` - the names that complete behind an
 * `@android:type/name` reference. The framework's `android.R` holds one nested class per resource type
 * (`android.R$string`, `android.R$color`, `android.R$drawable`, …), each declaring the framework resource
 * names as `public static final int` fields, so reading those field names yields the framework's public
 * resource set for the SDK's API level.
 *
 * Pure ASM + [ZipFile] (no class loading - android.jar targets Android and can't be loaded on the host; works
 * on desktop and ART). The framework resource set is fixed per platform jar, so the caller caches the result
 * by android.jar identity. Note the field *values* are framework resource ids, not literals, so framework
 * resources carry no value hint (only the names complete).
 */
object FrameworkResourceScanner {

    /** Framework resource names by [ResourceType], scanned from [androidJar]'s `android.R$*` classes (sorted). */
    fun scan(androidJar: Path): Map<ResourceType, List<String>> {
        val out = HashMap<ResourceType, MutableList<String>>()
        runCatching {
            ZipFile(androidJar.toFile()).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    if (!name.startsWith("android/R$") || !name.endsWith(".class")) continue
                    val type = ResourceType.byRClass(name.removePrefix("android/R$").removeSuffix(".class")) ?: continue
                    val names = out.getOrPut(type) { ArrayList() }
                    zip.getInputStream(entry).use { ins ->
                        ClassReader(ins.readBytes()).accept(
                            object : ClassVisitor(Opcodes.ASM9) {
                                override fun visitField(
                                    access: Int, fieldName: String, descriptor: String?, signature: String?, value: Any?,
                                ): FieldVisitor? {
                                    if (access and Opcodes.ACC_PUBLIC != 0 && access and Opcodes.ACC_STATIC != 0)
                                        names.add(fieldName)
                                    return null
                                }
                            },
                            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
                        )
                    }
                }
            }
        }
        return out.mapValues { (_, v) -> v.distinct().sorted() }
    }
}
