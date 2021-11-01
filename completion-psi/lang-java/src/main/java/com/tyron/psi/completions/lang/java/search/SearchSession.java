package com.tyron.psi.completions.lang.java.search;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.util.UserDataHolderBase;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtilCore;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.util.List;

/**
 * @author peter
 */
public class SearchSession extends UserDataHolderBase {
    private final PsiElement[] myElements;

    public SearchSession() {
        this(PsiElement.EMPTY_ARRAY);
    }
    public SearchSession(PsiElement... elements) {
        myElements = elements;
    }

    @NotNull
    public List<VirtualFile> getTargetVirtualFiles() {
        return ContainerUtil.mapNotNull(myElements, e -> PsiUtilCore.getVirtualFile(e));
    }
}
