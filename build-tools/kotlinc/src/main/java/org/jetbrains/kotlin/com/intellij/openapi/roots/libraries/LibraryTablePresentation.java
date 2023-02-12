package org.jetbrains.kotlin.com.intellij.openapi.roots.libraries;

import androidx.annotation.NonNull;

/**
 * @see LibraryTablesRegistrar#getLibraryTable(com.intellij.openapi.project.Project)
 */
public abstract class LibraryTablePresentation {

    @NonNull
    public abstract String getDisplayName(boolean plural);

    @NonNull
    public abstract String getDescription();

    @NonNull
    public abstract String getLibraryTableEditorTitle();

}