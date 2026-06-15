package dev.ide.build.kotlinc

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.TypeInsnNode

/**
 * Redirects the Kotlin compiler's "locate my own jar" probe to a real on-device directory.
 *
 * `PathUtil.getResourcePathForClass(clazz)` resolves the jar/dir a class was loaded from, by mapping the
 * class to a `.class` resource path. A dexed APK has **no `.class` resources**, so on ART the lookup returns
 * null and the method throws. The compiler uses the result for two things: (a) finding `$KOTLIN_HOME/lib`
 * for the bundled stdlib (we don't need it — `-no-stdlib -no-reflect -no-jdk` + an explicit classpath), and
 * (b) — the harder one — locating its **extension-point descriptor XMLs** (under `META-INF/extensions/`) to
 * boot IntelliJ-core's extension registry. Those XMLs must be read from a real filesystem path.
 *
 * So instead of a sentinel, we point it at a directory the app provisions at runtime: the compiler's
 * resources (the jar minus `.class` entries) are extracted there and the path is published in the
 * `kotlinc.art.home` system property (see the device spike / `bundleKotlincResourcesAsset`). The descriptor
 * loader then reads `$home/META-INF/extensions/compiler-cli-root.xml` directly — no jar, no NIO zip provider.
 *
 * The rewritten body is `return new File(System.getProperty("kotlinc.art.home", "<unset>"))`. The default is
 * a non-existent path, so if the property is unset the `$KOTLIN_HOME/lib` branch still degrades gracefully
 * (its `isFile()` check fails → dist-directory fallback); only the extension-XML path requires a real home.
 */
class PathUtilSelfLocatePass : ArtPatchPass {

    override val name: String = "pathutil-self-locate-redirect"

    override fun handles(classFqn: String): Boolean = classFqn == TARGET

    override fun visitor(classFqn: String, next: ClassVisitor): ClassVisitor = object : ClassNode(Opcodes.ASM9) {
        override fun visitEnd() {
            for (method in methods) {
                if (method.name == "getResourcePathForClass" && method.desc == GET_RESOURCE_PATH_DESC) {
                    method.instructions = InsnList().apply {
                        // new File(System.getProperty("kotlinc.art.home", "/nonexistent-kotlinc-home"))
                        add(TypeInsnNode(Opcodes.NEW, "java/io/File"))
                        add(InsnNode(Opcodes.DUP))
                        add(LdcInsnNode(HOME_PROPERTY))
                        add(LdcInsnNode(UNSET_DEFAULT))
                        add(
                            MethodInsnNode(
                                Opcodes.INVOKESTATIC, "java/lang/System", "getProperty",
                                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false,
                            ),
                        )
                        add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/io/File", "<init>", "(Ljava/lang/String;)V", false))
                        add(InsnNode(Opcodes.ARETURN))
                    }
                    method.tryCatchBlocks = ArrayList()
                    method.localVariables = ArrayList()
                    method.maxStack = 4
                    method.maxLocals = 1 // the (unused) Class<?> parameter
                }
            }
            accept(next)
        }
    }

    private companion object {
        const val TARGET = "org.jetbrains.kotlin.utils.PathUtil"
        const val GET_RESOURCE_PATH_DESC = "(Ljava/lang/Class;)Ljava/io/File;"
        const val HOME_PROPERTY = "kotlinc.art.home"
        const val UNSET_DEFAULT = "/nonexistent-kotlinc-home"
    }
}
