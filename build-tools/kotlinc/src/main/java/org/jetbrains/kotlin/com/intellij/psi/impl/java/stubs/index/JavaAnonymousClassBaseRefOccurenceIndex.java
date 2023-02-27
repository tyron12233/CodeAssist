package org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.index;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.psi.PsiAnonymousClass;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StringStubIndexExtension;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIndex;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIndexKey;

import java.util.Collection;

public class JavaAnonymousClassBaseRefOccurenceIndex extends StringStubIndexExtension<PsiAnonymousClass> {
  private static final JavaAnonymousClassBaseRefOccurenceIndex ourInstance = new JavaAnonymousClassBaseRefOccurenceIndex();

  public static JavaAnonymousClassBaseRefOccurenceIndex getInstance() {
    return ourInstance;
  }

  @NotNull
  @Override
  public StubIndexKey<String, PsiAnonymousClass> getKey() {
    return JavaStubIndexKeys.ANONYMOUS_BASEREF;
  }

  @Override
  public Collection<PsiAnonymousClass> get(@NotNull final String s, @NotNull final Project project, @NotNull final GlobalSearchScope scope) {
    return StubIndex.getElements(getKey(), s, project, new JavaSourceFilterScope(scope), PsiAnonymousClass.class);
  }
}