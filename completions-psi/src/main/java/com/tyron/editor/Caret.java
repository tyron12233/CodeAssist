package com.tyron.editor;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;

public interface Caret {

    Editor getEditor();

    boolean isValid();

    /**
     * Moves the caret by the specified number of lines and/or columns.
     *
     * @param columnShift    the number of columns to move the caret by.
     * @param lineShift      the number of lines to move the caret by.
     * @param withSelection  if true, the caret move should extend the selection in the document.
     * @param scrollToCaret  if true, the document should be scrolled so that the caret is visible after the move.
     */
    void moveCaretRelatively(int columnShift,
                             int lineShift,
                             boolean withSelection,
                             boolean scrollToCaret);


    /**
     * Short hand for calling {@link #moveToOffset(int, boolean)} with {@code 'false'} as a second argument.
     *
     * @param offset      the offset to move to
     */
    void moveToOffset(int offset);

    /**
     * Moves the caret to the specified offset in the document.
     * If corresponding position is in the folded region currently, the region will be expanded.
     *
     * @param offset                  the offset to move to.
     * @param locateBeforeSoftWrap    there is a possible case that there is a soft wrap at the given offset, hence, the same offset
     *                                corresponds to two different visual positions - just before soft wrap and just after soft wrap.
     *                                We may want to clearly indicate where to put the caret then. Given parameter allows to do that.
     *                                <b>Note:</b> it's ignored if there is no soft wrap at the given offset
     */
    void moveToOffset(int offset, boolean locateBeforeSoftWrap);

    /**
     * Tells whether caret is in consistent state currently. This might not be the case during document update, but client code can
     * observe such a state only in specific circumstances. So unless you're implementing very low-level editor logic (involving
     * {@code PrioritizedDocumentListener}), you don't need this method - you'll only see it return {@code true}.
     */
    boolean isUpToDate();

    /**
     * Returns the offset of the caret in the document. Returns 0 for a disposed (invalid) caret.
     *
     * @return the caret offset.
     *
     * @see #isValid()
     */
    int getOffset();

    /**
     * @return    document offset for the start of the visual line where caret is located
     */
    int getVisualLineStart();

    /**
     * @return    document offset that points to the first symbol shown at the next visual line after the one with caret on it
     */
    int getVisualLineEnd();

    /**
     * Returns the start offset in the document of the selected text range, or the caret
     * position if there is currently no selection.
     *
     * @return the selection start offset.
     */
    int getSelectionStart();

    /**
     * Returns the end offset in the document of the selected text range, or the caret
     * position if there is currently no selection.
     *
     * @return the selection end offset.
     */
    int getSelectionEnd();

    /**
     * Checks if a range of text is currently selected.
     *
     * @return true if a range of text is selected, false otherwise.
     */
    boolean hasSelection();

    /**
     * Selects the specified range of text.
     * <p>
     * System selection will be updated, if such feature is supported by current editor.
     *
     * @param startOffset the start offset of the text range to select.
     * @param endOffset   the end offset of the text range to select.
     */
    void setSelection(int startOffset, int endOffset);

    /**
     * Selects the specified range of text.
     *
     * @param startOffset the start offset of the text range to select.
     * @param endOffset   the end offset of the text range to select.
     * @param updateSystemSelection whether system selection should be updated (might not have any effect if current editor doesn't support such a feature)
     */
    void setSelection(int startOffset, int endOffset, boolean updateSystemSelection);


    /**
     * Returns the logical position of the caret.
     *
     * @return the caret position.
     */
    @NotNull
    LogicalPosition getLogicalPosition();

    /**
     * Returns the visual position of the caret.
     *
     * @return the caret position.
     */
    @NotNull
    VisualPosition getVisualPosition();

    /**
     * Moves the caret to the specified logical position.
     * If corresponding position is in the folded region currently, the region will be expanded.
     *
     * @param pos the position to move to.
     */
    void moveToLogicalPosition(@NotNull LogicalPosition pos);

    /**
     * Moves the caret to the specified visual position.
     *
     * @param pos the position to move to.
     */
    void moveToVisualPosition(@NotNull VisualPosition pos);


}
