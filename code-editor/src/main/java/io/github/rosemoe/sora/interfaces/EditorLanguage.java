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

import io.github.rosemoe.sora.widget.SymbolPairMatch;

/**
 * Language for editor
 * <p>
 * A Language helps editor to highlight text and provide auto-completion.
 * Implement this interface when you want to add new language support for editor.
 * <p>
 * <strong>NOTE:</strong> A language must not be single instance.
 * One language instance should always serves for only one editor.
 * It means that you should not give a language object to other editor instances
 * after it has been applied to one editor.
 * This is to provide better connection between auto completion provider and code analyzer.
 *
 * @author Rose
 */
public interface EditorLanguage {

    /**
     * Get CodeAnalyzer of this language object
     *
     * @return CodeAnalyzer
     */
    CodeAnalyzer getAnalyzer();

    /**
     * Get AutoCompleteProvider of this language object
     *
     * @return AutoCompleteProvider
     */
    AutoCompleteProvider getAutoCompleteProvider();

    /**
     * Called by editor to check whether this is a character for auto completion
     *
     * @param ch Character to check
     * @return Whether is character for auto completion
     */
    boolean isAutoCompleteChar(char ch);

    /**
     * Get advance for indent
     *
     * @param content Content of a line
     * @return Advance space count
     */
    int getIndentAdvance(String content);

    /**
     * Whether use tab to format
     *
     * @return Whether use tab
     */
    boolean useTab();

    /**
     * Format the given content
     *
     * @param text Content to format
     * @return Formatted code
     */
    CharSequence format(CharSequence text);

    /**
     * Returns language specified symbol pairs.
     * The method is called only once when the language is applied.
     */
    SymbolPairMatch getSymbolPairs();

    /**
     * Get newline handlers of this language.
     * This method is called each time the user presses ENTER key.
     * <p>
     * Pay attention to the performance as this method is called frequently
     *
     * @return NewlineHandlers , maybe null
     */
    NewlineHandler[] getNewlineHandlers();

}
