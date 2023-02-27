package org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.index;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.psi.PsiMember;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StringStubIndexExtension;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIndex;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIndexKey;

import java.util.Collection;

public class JavaStaticMemberTypeIndex extends StringStubIndexExtension<PsiMember> {
  private static final JavaStaticMemberTypeIndex ourInstance = new JavaStaticMemberTypeIndex();

  public static JavaStaticMemberTypeIndex getInstance() {
    return ourInstance;
  }

  @NotNull
  @Override
  public StubIndexKey<String, PsiMember> getKey() {
    return JavaStubIndexKeys.JVM_STATIC_MEMBERS_TYPES;
  }

  @NotNull
  public Collection<PsiMember> getStaticMembers(@NotNull String shortTypeText, @NotNull Project project, @NotNull GlobalSearchScope scope) {
    return StubIndex.getElements(JavaStubIndexKeys.JVM_STATIC_MEMBERS_TYPES, shortTypeText, project, new JavaSourceFilterScope(scope), PsiMember.class);
  }
}
