package com.tyron.completion.lookup;

import android.graphics.Rect;

import androidx.annotation.Nullable;

import com.tyron.completion.PrefixMatcher;
import com.tyron.editor.Editor;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.editor.colors.ColorKey;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;

import java.util.List;

import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * Represents list with suggestions shown in code completion, refactorings, live templates etc.
 */
public interface Lookup {
  char NORMAL_SELECT_CHAR = '\n';
  char REPLACE_SELECT_CHAR = '\t';
  char COMPLETE_STATEMENT_SELECT_CHAR = '\r';
  char AUTO_INSERT_SELECT_CHAR = (char) 0;
  ColorKey LOOKUP_COLOR = ColorKey.createColorKey("LOOKUP_COLOR");

  /**
   * @return the offset in {@link #getTopLevelEditor()} which this lookup's left side should be aligned with. Note that if the lookup doesn't fit
   * the screen due to its dimensions, the actual position might differ from this editor offset.
   */
  int getLookupStart();

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

  /**
   * @return leaf PSI element at this lookup's start position (see {@link #getLookupStart()}) in {@link #getPsiFile()} result.
   */
  @Nullable
  PsiElement getPsiElement();

  /**
   * Consider using {@link #getTopLevelEditor()} if you don't need injected editor.
   * @return editor, possibly injected, where this lookup is shown
   */
  @NotNull Editor getEditor();

  /**
   * @return the non-injected editor where this lookup is shown
   */
  @NotNull
  Editor getTopLevelEditor();

  @NotNull Project getProject();

  /**
   * @return PSI file, possibly injected, associated with this lookup's editor
   * @see #getEditor()
   */
  @Nullable
  PsiFile getPsiFile();

  boolean isCompletion();

  List<LookupElement> getItems();

  boolean isFocused();

  @NotNull
  String itemPattern(@NotNull LookupElement element);

  @NotNull PrefixMatcher itemMatcher(@NotNull LookupElement item);

  boolean isSelectionTouched();

  List<String> getAdvertisements();
}