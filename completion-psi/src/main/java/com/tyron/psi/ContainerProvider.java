package com.tyron.psi;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;

/**
 * @author Max Medvedev
 */
public interface ContainerProvider {
    ExtensionPointName<ContainerProvider> EP_NAME = ExtensionPointName.create("com.intellij.codeInsight.containerProvider");

    @Nullable
    PsiElement getContainer(@NotNull PsiElement item);
}
