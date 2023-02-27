package org.jetbrains.kotlin.com.intellij.psi.stubs;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.util.Processor;

import java.util.Collection;

public abstract class AbstractStubIndex<Key, Psi extends PsiElement> implements StubIndexExtension<Key, Psi> {
  public Collection<Key> getAllKeys(Project project) {
    return StubIndex.getInstance().getAllKeys(getKey(), project);
  }

  public boolean processAllKeys(Project project, Processor<? super Key> processor) {
    return StubIndex.getInstance().processAllKeys(getKey(), project, processor);
  }

  public Collection<Psi> get(@NonNull Key key, @NonNull final Project project, @NonNull final GlobalSearchScope scope) {
    return StubIndex.getInstance().get(getKey(), key, project, scope);
  }

  @Override
  public int getCacheSize() { return 2 * 1024; }
}