package com.tyron.completion;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.lang.Language;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;

import java.util.Objects;

import io.github.rosemoe.sora.text.Cursor;
import io.github.rosemoe.sora.widget.CodeEditor;

public class CompletionInitializationContext {
  public static final OffsetKey START_OFFSET = OffsetKey.create("startOffset", false);
  public static final OffsetKey SELECTION_END_OFFSET = OffsetKey.create("selectionEnd");
  public static final OffsetKey IDENTIFIER_END_OFFSET = OffsetKey.create("identifierEnd");

  /**
   * A default string that is inserted into the file before completion to guarantee that there'll always be some non-empty element there
   */
  public static @NonNls final String DUMMY_IDENTIFIER = CompletionUtilCore.DUMMY_IDENTIFIER;
  public static @NonNls final String DUMMY_IDENTIFIER_TRIMMED = CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED;
  private final CodeEditor myEditor;
  @NotNull
  private final Cursor myCaret;
  private final PsiFile myFile;
  private final CompletionType myCompletionType;
  private final int myInvocationCount;
  private final OffsetMap myOffsetMap;
  private String myDummyIdentifier = DUMMY_IDENTIFIER;
  private final Language myPositionLanguage;

  public CompletionInitializationContext(final CodeEditor editor,
                                         final @NotNull Cursor caret,
                                         Language language,
                                         final PsiFile file,
                                         final CompletionType completionType,
                                         int invocationCount) {
    myEditor = editor;
    myCaret = caret;
    myPositionLanguage = language;
    myFile = file;
    myCompletionType = completionType;
    myInvocationCount = invocationCount;
    myOffsetMap = new OffsetMap(EditorMemory.getUserData(editor, EditorMemory.DOCUMENT_KEY));

    myOffsetMap.addOffset(START_OFFSET, calcStartOffset(caret));
    myOffsetMap.addOffset(SELECTION_END_OFFSET, calcSelectionEnd(caret));
    myOffsetMap.addOffset(IDENTIFIER_END_OFFSET, calcDefaultIdentifierEnd(editor, calcSelectionEnd(caret)));
  }

  @ApiStatus.Internal
  public static int calcSelectionEnd(Cursor caret) {
    return caret.isSelected() ? caret.getRight() : caret.getLeft();
  }

  public static int calcStartOffset(Cursor caret) {
    return caret.getLeft();
  }

  public static int calcDefaultIdentifierEnd(CodeEditor editor, int startFrom) {
    final CharSequence text = editor.getText();
    int idEnd = startFrom;
    while (idEnd < text.length() && Character.isJavaIdentifierPart(text.charAt(idEnd))) {
      idEnd++;
    }
    return idEnd;
  }

  public void setDummyIdentifier(@NotNull String dummyIdentifier) {
    myDummyIdentifier = dummyIdentifier;
  }

  @NotNull
  public Language getPositionLanguage() {
    return Objects.requireNonNull(myPositionLanguage);
  }

  public String getDummyIdentifier() {
    return myDummyIdentifier;
  }

  @NotNull
  public CodeEditor getEditor() {
    return myEditor;
  }

  @NotNull
  public Cursor getCaret() {
    return myCaret;
  }

  @NotNull
  public CompletionType getCompletionType() {
    return myCompletionType;
  }

  @NotNull
  public Project getProject() {
    return myFile.getProject();
  }

  @NotNull
  public PsiFile getFile() {
    return myFile;
  }

  @NotNull
  public OffsetMap getOffsetMap() {
    return myOffsetMap;
  }

  public int getStartOffset() {
    return myOffsetMap.getOffset(START_OFFSET);
  }

  public int getSelectionEndOffset() {
    return myOffsetMap.getOffset(SELECTION_END_OFFSET);
  }

  public int getIdentifierEndOffset() {
    return myOffsetMap.getOffset(IDENTIFIER_END_OFFSET);
  }

  public int getReplacementOffset() {
    return getIdentifierEndOffset();
  }

  public int getInvocationCount() {
    return myInvocationCount;
  }

  /**
   * Mark the offset up to which the text will be deleted if a completion variant is selected using Replace character (Tab)
   */
  public void setReplacementOffset(int idEnd) {
    myOffsetMap.addOffset(IDENTIFIER_END_OFFSET, idEnd);
  }
}