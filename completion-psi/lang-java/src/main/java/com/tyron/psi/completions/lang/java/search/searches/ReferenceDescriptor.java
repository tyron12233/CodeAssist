package com.tyron.psi.completions.lang.java.search.searches;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiReference;
import org.jetbrains.kotlin.com.intellij.util.Function;

public final class ReferenceDescriptor {
    @NotNull
    public static final Function<PsiReference, ReferenceDescriptor> MAPPER = psiReference -> {
        final PsiElement element = psiReference.getElement();
        final PsiFile file1 = element.getContainingFile();
        TextRange textRange = element.getTextRange();
        int startOffset = textRange != null ? textRange.getStartOffset() : 0;
        return new ReferenceDescriptor(file1.getViewProvider().getVirtualFile(), startOffset + psiReference.getRangeInElement().getStartOffset());
    };
    private final VirtualFile file;
    private final int offset;

    private ReferenceDescriptor(@NotNull VirtualFile file, int offset) {
        this.file = file;
        this.offset = offset;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ReferenceDescriptor)) return false;

        ReferenceDescriptor that = (ReferenceDescriptor)o;

        if (offset != that.offset) return false;
        return file.equals(that.file);
    }

    @Override
    public int hashCode() {
        return 31 * file.hashCode() + offset;
    }
}
