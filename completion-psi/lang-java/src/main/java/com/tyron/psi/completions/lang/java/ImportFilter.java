package com.tyron.psi.completions.lang.java;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;

/**
 * @author Eugene.Kudelevsky
 */
public abstract class ImportFilter {
    public static final ExtensionPointName<ImportFilter> EP_NAME = new ExtensionPointName<>("com.intellij.importFilter");

    public abstract boolean shouldUseFullyQualifiedName(@NotNull PsiFile targetFile, @NotNull String classQualifiedName);

    public static boolean shouldImport(@NotNull PsiFile targetFile, @NotNull String classQualifiedName) {
        for (ImportFilter filter : EP_NAME.getExtensions()) {
            if (filter.shouldUseFullyQualifiedName(targetFile, classQualifiedName)) {
                return false;
            }
        }
        return true;
    }
}