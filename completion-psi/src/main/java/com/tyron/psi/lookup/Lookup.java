package com.tyron.psi.lookup;

import android.graphics.Rect;

import com.tyron.psi.completion.PrefixMatcher;
import com.tyron.psi.editor.Editor;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;

import java.util.List;

public interface Lookup {
    char NORMAL_SELECT_CHAR = '\n';
    char REPLACE_SELECT_CHAR = '\t';
    char COMPLETE_STATEMENT_SELECT_CHAR = '\r';
    char AUTO_INSERT_SELECT_CHAR = (char) 0;

    @Nullable
    LookupElement getCurrentItem();

    void addLookupListener(LookupListener listener);
    void removeLookupListener(LookupListener listener);

    /**
     * @return bounds in layered pane coordinate system
     */
    Rect getBounds();

    /**
     * @return bounds of the current item in the layered pane coordinate system.
     */
    Rect getCurrentItemBounds();
    boolean isPositionedAboveCaret();

    @Nullable
    PsiElement getPsiElement();

    Editor getEditor();

    PsiFile getPsiFile();

    boolean isCompletion();

    List<LookupElement> getItems();

    boolean isFocused();

    @NotNull
    String itemPattern(@NotNull LookupElement element);

    @NotNull
    PrefixMatcher itemMatcher(@NotNull LookupElement item);

    boolean isSelectionTouched();

    List<String> getAdvertisements();
}