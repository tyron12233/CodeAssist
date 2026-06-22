package dev.ide.build.kotlinc

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes

/**
 * Strips the absent `javax.swing.Icon` superinterface from IntelliJ-core's icon marker interfaces so they
 * link on ART.
 *
 * IntelliJ's icon system is built on Swing: `org.jetbrains.kotlin.com.intellij.ui.icons.ReplaceableIcon`
 * and `CompositeIcon` declare `extends javax.swing.Icon`, and `RowIcon extends CompositeIcon`. ART has no
 * `javax.swing` (and it cannot be supplied as a compiled stub: the `javax.swing` package is owned by the
 * JDK's `java.desktop` module, so javac refuses to define into it). So loading any of these interfaces fails
 * with `NoClassDefFoundError: javax/swing/Icon`, which is reached the moment the editor warms up the Kotlin
 * parse host: `KotlinCoreEnvironment.createForProduction` registers `KotlinJavaPsiFacade`, whose `<clinit>`
 * constructs a `PsiPackageImpl` and pulls in the PSI icon types. The failure disables Kotlin parsing,
 * completion and analysis.
 *
 * These are pure marker interfaces with no methods of their own; a headless compiler never renders or
 * fetches an icon (`IconManager.getInstance()` returns a `DummyIconManager` only when called, and is not on
 * any compile path), so being a `javax.swing.Icon` is never relied on. Dropping that one superinterface lets
 * the interfaces link with no behavioral effect. The concrete `Dummy*` icon impls keep their own
 * `paintIcon(java.awt.Component, …)` bodies and stay unloadable, but they are never loaded on a compile path.
 *
 * Scoped to the `…com.intellij.ui.icons.` package (exactly `ReplaceableIcon`/`CompositeIcon`/`RowIcon` in the
 * bundled compiler) so only the icon markers are touched.
 */
class SwingIconArtPass : ArtPatchPass {

    override val name: String = "swing-icon-interface-strip"

    override fun handles(classFqn: String): Boolean =
        classFqn.startsWith("org.jetbrains.kotlin.com.intellij.ui.icons.")

    override fun visitor(classFqn: String, next: ClassVisitor): ClassVisitor =
        object : ClassVisitor(Opcodes.ASM9, next) {
            override fun visit(
                version: Int,
                access: Int,
                name: String?,
                signature: String?,
                superName: String?,
                interfaces: Array<out String>?,
            ) {
                val filtered = interfaces?.filterNot { it == SWING_ICON }?.toTypedArray()
                // Drop the generic signature too: it is null for these non-generic markers, but nulling it
                // guarantees a `Ljavax/swing/Icon;` reference there can never re-introduce the dependency.
                super.visit(version, access, name, null, superName, filtered)
            }
        }

    private companion object {
        const val SWING_ICON = "javax/swing/Icon"
    }
}
