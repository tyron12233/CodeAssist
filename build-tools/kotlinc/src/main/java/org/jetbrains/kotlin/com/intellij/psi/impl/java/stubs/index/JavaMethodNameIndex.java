package org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.index;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StringStubIndexExtension;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIndex;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIndexKey;

import java.util.Collection;

public class JavaMethodNameIndex extends StringStubIndexExtension<PsiMethod> {
  private static final JavaMethodNameIndex ourInstance = new JavaMethodNameIndex();

  public static JavaMethodNameIndex getInstance() {
    return ourInstance;
  }

  @NotNull
  @Override
  public StubIndexKey<String, PsiMethod> getKey() {
    return JavaStubIndexKeys.METHODS;
  }

  @Override
  public Collection<PsiMethod> get(@NotNull final String methodName, @NotNull final Project project, @NotNull final GlobalSearchScope scope) {
    return StubIndex.getElements(getKey(), methodName, project, new JavaSourceFilterScope(scope), PsiMethod.class);
  }
}