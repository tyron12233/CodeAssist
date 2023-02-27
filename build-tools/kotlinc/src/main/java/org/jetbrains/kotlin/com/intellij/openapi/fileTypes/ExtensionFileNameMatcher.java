package org.jetbrains.kotlin.com.intellij.openapi.fileTypes;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.Strings;

public class ExtensionFileNameMatcher implements FileNameMatcher {
  private final String myExtension;
  private final String myDotExtension;

  public ExtensionFileNameMatcher(@NotNull String extension) {
    myExtension = Strings.toLowerCase(extension);
    myDotExtension = "." + myExtension;
    if (extension.contains("*") || extension.contains("?")) {
      throw new IllegalArgumentException("extension should not contain regexp but got: '"+extension+"'");
    }
  }

  @Override
  public boolean acceptsCharSequence(@NotNull CharSequence fileName) {
    return Strings.endsWithIgnoreCase(fileName, myDotExtension);
  }

  @Override
  public @NotNull String getPresentableString() {
    return "*." + myExtension;
  }

  @NotNull
  public String getExtension() {
    return myExtension;
  }

  @Override
  public boolean equals(final Object o) {
      if (this == o) {
          return true;
      }
      if (o == null || getClass() != o.getClass()) {
          return false;
      }

    final ExtensionFileNameMatcher that = (ExtensionFileNameMatcher)o;

    return myExtension.equals(that.myExtension);
  }

  @Override
  public int hashCode() {
    return myExtension.hashCode();
  }

  @Override
  public String toString() {
    return getPresentableString();
  }
}