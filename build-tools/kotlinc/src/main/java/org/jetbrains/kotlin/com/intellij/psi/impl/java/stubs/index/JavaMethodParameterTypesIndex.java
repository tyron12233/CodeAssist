package org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.index;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StringStubIndexExtension;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIndex;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIndexKey;

import java.util.Collection;

public class JavaMethodParameterTypesIndex extends StringStubIndexExtension<PsiMethod> {
  private static final JavaMethodParameterTypesIndex ourInstance = new JavaMethodParameterTypesIndex();

  public static JavaMethodParameterTypesIndex getInstance() {
    return ourInstance;
  }

  @Override
  public int getVersion() {
    return super.getVersion() + 1;
  }

  @NotNull
  @Override
  public StubIndexKey<String, PsiMethod> getKey() {
    return JavaStubIndexKeys.METHOD_TYPES;
  }

  @Override
  public Collection<PsiMethod> get(@NotNull final String s, @NotNull final Project project, @NotNull final GlobalSearchScope scope) {
    return StubIndex.getElements(getKey(), s, project, new JavaSourceFilterScope(scope), PsiMethod.class);
  }
}