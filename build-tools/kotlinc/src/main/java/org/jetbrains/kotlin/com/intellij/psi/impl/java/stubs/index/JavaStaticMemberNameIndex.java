package org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.index;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.psi.PsiMember;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StringStubIndexExtension;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIndex;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIndexKey;

import java.util.Collection;

public class JavaStaticMemberNameIndex extends StringStubIndexExtension<PsiMember> {
  private static final JavaStaticMemberNameIndex ourInstance = new JavaStaticMemberNameIndex();

  public static JavaStaticMemberNameIndex getInstance() {
    return ourInstance;
  }

  @NotNull
  @Override
  public StubIndexKey<String, PsiMember> getKey() {
    return JavaStubIndexKeys.JVM_STATIC_MEMBERS_NAMES;
  }

  public Collection<PsiMember> getStaticMembers(final String name, final Project project, @NotNull final GlobalSearchScope scope) {
    return StubIndex.getElements(getKey(), name, project, new JavaSourceFilterScope(scope), PsiMember.class);
  }
}