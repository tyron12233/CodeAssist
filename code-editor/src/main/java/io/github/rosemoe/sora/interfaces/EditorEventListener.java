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
package io.github.rosemoe.sora.interfaces;

import androidx.annotation.NonNull;

import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.Cursor;
import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * Listener for editor
 *
 * @author Rose
 */
public interface EditorEventListener {

    /**
     * Before format
     *
     * @return true if you want to cancel this operation
     */
    boolean onRequestFormat(@NonNull CodeEditor editor);

    /**
     * Format failed
     *
     * @return true if you do not want editor to handle this exception
     */
    boolean onFormatFail(@NonNull CodeEditor editor, Throwable cause);

    /**
     * Format succeeded
     */
    void onFormatSucceed(@NonNull CodeEditor editor);

    /**
     * {@link CodeEditor#setText(CharSequence)} is called
     */
    void onNewTextSet(@NonNull CodeEditor editor);

    /**
     * @see io.github.rosemoe.sora.text.ContentListener#afterDelete(Content, int, int, int, int, CharSequence)
     * Note:do not change content at this time
     */
    void afterDelete(@NonNull CodeEditor editor, @NonNull CharSequence content, int startLine, int startColumn, int endLine, int endColumn, CharSequence deletedContent);

    /**
     * @see io.github.rosemoe.sora.text.ContentListener#afterInsert(Content, int, int, int, int, CharSequence)
     * Note:do not change content at this time
     */
    void afterInsert(@NonNull CodeEditor editor, @NonNull CharSequence content, int startLine, int startColumn, int endLine, int endColumn, CharSequence insertedContent);

    /**
     * @see io.github.rosemoe.sora.text.ContentListener#beforeReplace(Content)
     * Note:do not change content at this time
     */
    void beforeReplace(@NonNull CodeEditor editor, @NonNull CharSequence content);

    /**
     * Called when selection is changed
     */
    void onSelectionChanged(@NonNull CodeEditor editor, @NonNull Cursor cursor);

}
