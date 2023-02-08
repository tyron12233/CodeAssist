package org.jetbrains.kotlin.com.intellij.psi.stubs;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.util.io.KeyDescriptor;

/**
 * Definition of the index. Implement the
 * {@link IStubElementType#indexStub(Stub, IndexSink)}) function
 * in your language's Stub Elements to fill the index with data.
 *
 * @see IStubElementType#indexStub(Stub, IndexSink)}
 */
public interface StubIndexExtension<Key, Psi extends PsiElement> {
  ExtensionPointName<StubIndexExtension<?, ?>> EP_NAME = ExtensionPointName.create("com.intellij.stubIndex");

  @NonNull
  StubIndexKey<Key, Psi> getKey();

  int getVersion();

  @NonNull
  KeyDescriptor<Key> getKeyDescriptor();

  int getCacheSize();
}