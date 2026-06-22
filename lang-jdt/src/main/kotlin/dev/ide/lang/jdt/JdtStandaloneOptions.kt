package dev.ide.lang.jdt

import org.eclipse.jdt.core.JavaCore
import java.util.Hashtable

/**
 * The JDT compiler options for a standalone DOM/diagnostic parse at [complianceVersion] (a
 * `JavaCore.VERSION_*` string), built WITHOUT [JavaCore.getOptions] OR [JavaCore.setComplianceOptions].
 *
 * Both of those reach into the Eclipse workspace runtime, which is not dexed on-device (only ecj and a
 * trimmed jdt.core/runtime are): `JavaCore.getOptions` initializes `JavaModelManager`, and
 * `JavaCore.setComplianceOptions`'s body touches `org.eclipse.core.runtime.jobs.*` /
 * `org.eclipse.core.resources.*`. On ART either fails with NoClassDefFoundError, which disables editor
 * analysis and floods logcat. The `JavaCore` class itself loads fine and its `COMPILER_*` keys are plain
 * String constants, so we set the compliance trio (compliance/source/target) by hand instead. Every parse
 * here is configured standalone (sourcepath/classpath set explicitly) and never touches a workspace, so
 * this is equivalent; ecj fills any option left unset (warning levels, etc.) from its own defaults. Doc
 * comment support is enabled because the DOM needs it for Javadoc nodes.
 */
internal fun jdtStandaloneCompilerOptions(complianceVersion: String): MutableMap<String, String> {
    val options = Hashtable<String, String>()
    options[JavaCore.COMPILER_COMPLIANCE] = complianceVersion
    options[JavaCore.COMPILER_SOURCE] = complianceVersion
    options[JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM] = complianceVersion
    options[JavaCore.COMPILER_DOC_COMMENT_SUPPORT] = JavaCore.ENABLED
    return options
}
