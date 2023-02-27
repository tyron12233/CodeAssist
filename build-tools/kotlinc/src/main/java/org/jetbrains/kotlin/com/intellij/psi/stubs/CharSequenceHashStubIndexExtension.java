package org.jetbrains.kotlin.com.intellij.psi.stubs;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.util.io.KeyDescriptor;

public abstract class CharSequenceHashStubIndexExtension<Psi extends PsiElement> extends AbstractStubIndex<CharSequence, Psi> {

  @Override
  public int getVersion() {
    return 1;
  }

  @Override
  @NonNull
  public final KeyDescriptor<CharSequence> getKeyDescriptor() {
    return CharSequenceHashInlineKeyDescriptor.INSTANCE;
  }

  public boolean doesKeyMatchPsi(@NonNull CharSequence key, @NonNull Psi psi) {
    return true;
  }
}