package org.jetbrains.kotlin.com.intellij.openapi.roots.libraries;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;

import java.util.List;

public abstract class LibraryTablesRegistrar {
  public static final String PROJECT_LEVEL = "project";
  public static final String APPLICATION_LEVEL = "application";

  public static LibraryTablesRegistrar getInstance() {
    return ApplicationManager.getApplication().getService(LibraryTablesRegistrar.class);
  }

  /**
   * Returns the table containing application-level libraries. These libraries are shown in 'Project Structure' | 'Platform Settings' | 'Global Libraries'
   * and may be added to dependencies of modules in any project.
   */
  public abstract @NonNull LibraryTable getLibraryTable();

  /**
   * Returns the table containing project-level libraries for given {@code project}. These libraries are shown in 'Project Structure'
   * | 'Project Settings' | 'Libraries' and may be added to dependencies of the corresponding project's modules only.
   */
  public abstract @NonNull LibraryTable getLibraryTable(@NonNull Project project);

  /**
   * Returns the standard or a custom library table registered via {@link CustomLibraryTableDescription}.
   */
  public abstract @Nullable LibraryTable getLibraryTableByLevel(String level, @NonNull Project project);

  /**
   * Returns a custom library table registered via {@link CustomLibraryTableDescription}.
   */
  public abstract @Nullable LibraryTable getCustomLibraryTableByLevel(String level);

  public abstract @NonNull List<LibraryTable> getCustomLibraryTables();
}