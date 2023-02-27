package org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.index;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.psi.stubs.AbstractStubIndex;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIndex;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIndexKey;
import org.jetbrains.kotlin.com.intellij.util.io.EnumeratorIntegerDescriptor;
import org.jetbrains.kotlin.com.intellij.util.io.KeyDescriptor;

import java.util.Collection;

public class JavaFullClassNameIndex extends AbstractStubIndex<Integer, PsiClass> {

  public static final StubIndexKey<Integer, PsiClass> INDEX_KEY = JavaStubIndexKeys.CLASS_FQN;
  private static final JavaFullClassNameIndex ourInstance = new JavaFullClassNameIndex();

  public static JavaFullClassNameIndex getInstance() {
    return ourInstance;
  }


  @NonNull
  @Override
  public KeyDescriptor<Integer> getKeyDescriptor() {
    return new EnumeratorIntegerDescriptor();
  }

  @Override
  public @NotNull StubIndexKey<Integer, PsiClass> getKey() {
    return INDEX_KEY;
  }

  @Override
  public int getVersion() {
    return 0;
  }

  @Override
  public Collection<PsiClass> get(@NotNull Integer name, @NotNull Project project, @NotNull GlobalSearchScope scope) {
    return StubIndex.getElements(getKey(), name, project, new JavaSourceFilterScope(scope), PsiClass.class);
  }

  public boolean doesKeyMatchPsi(@NotNull Integer key, @NotNull PsiClass aClass) {
    return key.equals(aClass.getQualifiedName().hashCode());
  }
}