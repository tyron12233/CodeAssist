package org.jetbrains.kotlin.com.intellij.psi.stubs;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.util.Processor;
import org.jetbrains.kotlin.com.intellij.util.indexing.IdFilter;
import org.jetbrains.kotlin.com.intellij.util.io.EnumeratorStringDescriptor;
import org.jetbrains.kotlin.com.intellij.util.io.KeyDescriptor;

public abstract class StringStubIndexExtension<Psi extends PsiElement> extends AbstractStubIndex<String, Psi> {
  @Override
  public int getVersion() {
    return 2;
  }

  @Override
  @NonNull
  public KeyDescriptor<String> getKeyDescriptor() {
    return EnumeratorStringDescriptor.INSTANCE;
  }

  /**
   * If true then {@code <key hash> -> <virtual file id>} mapping will be saved in the persistent index structure.
   * It will then be used inside {@link StubIndex#processAllKeys(StubIndexKey, Processor, GlobalSearchScope, IdFilter)},
   * accepting {@link IdFilter} as a coarse filter to exclude keys from unrelated virtual files from further processing.
   * Otherwise, {@link IdFilter} parameter of this method will be ignored.
   * <p>
   * This property might come useful for optimizing "Go to Class/Symbol" and completion performance in case of multiple indexed projects.
   *
   * @see IdFilter#buildProjectIdFilter(Project, boolean)
   */
  public boolean traceKeyHashToVirtualFileMapping() {
    return false;
  }
}