package dev.ide.lang.java.index

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.impl.source.PsiExtensibleClass

/**
 * OWN (before-augmentation) member accessors for the structural indexer.
 *
 * Indexing parses on the shared classpath-free `IntellijPsiHost` project, which — unlike the editor's
 * resolution environments — is deliberately NOT given a `PomModel` (see [dev.ide.lang.java.env.JavaRecordSupport]).
 * With record augmentation registered app-wide, `PsiClass.getMethods()`/`getFields()` on a source record would
 * try to synthesize its accessors/backing-fields there and THROW (`PomManager.getModel must not return null`),
 * which the indexer's `runCatching` would swallow into an empty result — dropping the record from the index.
 *
 * Reading OWN members bypasses augmentation entirely, so indexing sees exactly what the source declares — the
 * same thing it saw before record augmentation existed. For a non-record (or any class with no augments) the
 * own list equals the full list, so this is behaviour-preserving; the fallback keeps a light/synthetic class
 * that isn't a [PsiExtensibleClass] working.
 */
internal fun PsiClass.ownMethodsSafe(): List<PsiMethod> =
    (this as? PsiExtensibleClass)?.ownMethods ?: methods.asList()

internal fun PsiClass.ownFieldsSafe(): List<PsiField> =
    (this as? PsiExtensibleClass)?.ownFields ?: fields.asList()

internal fun PsiClass.ownInnerClassesSafe(): List<PsiClass> =
    (this as? PsiExtensibleClass)?.ownInnerClasses ?: innerClasses.asList()
