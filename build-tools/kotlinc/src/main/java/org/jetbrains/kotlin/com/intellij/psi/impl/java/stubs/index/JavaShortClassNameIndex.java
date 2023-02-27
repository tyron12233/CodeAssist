package org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.index;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StringStubIndexExtension;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIndex;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIndexKey;

import java.util.Collection;

public class JavaShortClassNameIndex extends StringStubIndexExtension<PsiClass> {
  private static final JavaShortClassNameIndex ourInstance = new JavaShortClassNameIndex();

  public static JavaShortClassNameIndex getInstance() {
    return ourInstance;
  }

  @Override
  public int getVersion() {
    return super.getVersion() + 2;
  }

  @NotNull
  @Override
  public StubIndexKey<String, PsiClass> getKey() {
    return JavaStubIndexKeys.CLASS_SHORT_NAMES;
  }

  @Override
  public Collection<PsiClass> get(@NotNull final String shortName, @NotNull final Project project, @NotNull final GlobalSearchScope scope) {
    return StubIndex.getElements(getKey(), shortName, project, new JavaSourceFilterScope(scope), PsiClass.class);
  }

  @Override
  public boolean traceKeyHashToVirtualFileMapping() {
    return true;
  }
}