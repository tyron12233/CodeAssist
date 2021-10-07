/*
 *    CodeEditor - the awesome code editor for Android
 *    Copyright (C) 2020-2021  Rosemoe
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 *
 *     Please contact Rosemoe by email 2073412493@qq.com if you need
 *     additional information or have any questions
 */
package io.github.rosemoe.sora.widget;

import io.github.rosemoe.sora.text.Cursor;

/**
 * A channel to insert symbols in {@link CodeEditor}
 * @author Rosemoe
 */
public class SymbolChannel {

    private CodeEditor mEditor;

    protected SymbolChannel(CodeEditor editor) {
        mEditor = editor;
    }

    /**
     * Inserts the given text in the editor.
     * <p>
     * This method allows you to insert texts externally to the content of editor.
     * The content of {@param symbolText} is not checked to be exactly characters of symbols.
     *
     * @throws IllegalArgumentException If the {@param selectionRegion} is invalid
     * @param symbolText Text to insert, usually a text of symbols
     * @param selectionOffset New selection position relative to the start of text to insert.
     *                        Ranging from 0 to symbolText.length()
     */
    public void insertSymbol(String symbolText, int selectionOffset) {
        if (selectionOffset < 0 || selectionOffset > symbolText.length()) {
            throw new IllegalArgumentException("selectionOffset is invalid");
        }
        Cursor cur = mEditor.getText().getCursor();
        if (cur.isSelected()) {
            cur.onDeleteKeyPressed();
            mEditor.notifyExternalCursorChange();
        }
        mEditor.getText().insert(cur.getRightLine(), cur.getRightColumn(), symbolText);
        mEditor.notifyExternalCursorChange();
        if (selectionOffset != symbolText.length()) {
            mEditor.setSelection(cur.getRightLine(), cur.getRightColumn() - (symbolText.length() - selectionOffset));
        }
    }

}
