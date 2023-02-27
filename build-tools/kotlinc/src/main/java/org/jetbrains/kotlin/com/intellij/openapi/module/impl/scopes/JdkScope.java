package org.jetbrains.kotlin.com.intellij.openapi.module.impl.scopes;

import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.roots.JdkOrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderRootType;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

import java.util.Objects;

public class JdkScope extends LibraryScopeBase {
  private final @Nullable String myJdkName;

  public JdkScope(Project project, JdkOrderEntry entry) {
    this(project, entry.getRootFiles(OrderRootType.CLASSES), entry.getRootFiles(OrderRootType.SOURCES), entry.getJdkName());
  }

  public JdkScope(Project project,
                  VirtualFile[] classes,
                  VirtualFile[] sources,
                  @Nullable String jdkName) {
    super(project, classes, sources);
    myJdkName = jdkName;
  }

  @Override
  public int calcHashCode() {
    return 31 * super.calcHashCode() + (myJdkName == null ? 0 : myJdkName.hashCode());
  }

  @Override
  public boolean equals(Object object) {
      if (object == null) {
          return false;
      }
      if (object == this) {
          return true;
      }
      if (object.getClass() != getClass()) {
          return false;
      }

    return Objects.equals(myJdkName, ((JdkScope)object).myJdkName) && super.equals(object);
  }
}