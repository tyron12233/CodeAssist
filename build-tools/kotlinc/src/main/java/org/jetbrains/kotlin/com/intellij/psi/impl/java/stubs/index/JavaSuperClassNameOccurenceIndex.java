package org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.index;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceList;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StringStubIndexExtension;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIndex;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIndexKey;

import java.util.Collection;

public class JavaSuperClassNameOccurenceIndex extends StringStubIndexExtension<PsiReferenceList> {
  private static final int VERSION = 1;
  private static final JavaSuperClassNameOccurenceIndex ourInstance = new JavaSuperClassNameOccurenceIndex();

  public static JavaSuperClassNameOccurenceIndex getInstance() {
    return ourInstance;
  }

  @NotNull
  @Override
  public StubIndexKey<String, PsiReferenceList> getKey() {
    return JavaStubIndexKeys.SUPER_CLASSES;
  }

  @Override
  public Collection<PsiReferenceList> get(@NotNull final String s, @NotNull final Project project, @NotNull final GlobalSearchScope scope) {
    return StubIndex.getElements(getKey(), s, project, new JavaSourceFilterScope(scope), PsiReferenceList.class);
  }

  @Override
  public int getVersion() {
    return super.getVersion() + VERSION;
  }
}