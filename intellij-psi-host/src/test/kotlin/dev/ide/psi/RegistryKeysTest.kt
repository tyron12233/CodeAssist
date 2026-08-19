package dev.ide.psi

import com.intellij.openapi.util.registry.Registry
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The registry keys IntelliJ's Java PSI reads must resolve on this host, which embeds the platform jars and
 * loads no plugin descriptors. An undefined key makes `Registry.is` throw, and the throw escapes whatever
 * resolution asked for it, so one generic call with a lambda argument used to abort a whole file's analysis.
 * See `IntellijPsiHost.contributeJavaPsiRegistryKeys`.
 *
 * The assertion is on the resolved VALUE, not merely on resolution not throwing: a descriptor built with its
 * arguments in the wrong order still resolves, it just resolves to the empty string, which reads as `false`
 * and quietly changes how inference behaves.
 */
class RegistryKeysTest {

    @Test
    fun javaPsiRegistryKeysResolveToTheirDeclaredDefaults() {
        IntellijPsiHost.warmUp()
        for (key in listOf(
            "javac.fresh.variables.for.captured.wildcards.only",
            "javac.unchecked.subtyping.during.incorporation",
            "java.correct.class.type.by.place.resolve.scope",
            "java.folding.icons.for.control.flow",
        )) {
            assertEquals("true", Registry.get(key).asString(), "registry key '$key' should carry IntelliJ's declared default")
        }
    }
}
