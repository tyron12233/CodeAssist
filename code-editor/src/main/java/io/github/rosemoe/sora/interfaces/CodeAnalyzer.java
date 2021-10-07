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

import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.TextAnalyzeResult;
import io.github.rosemoe.sora.text.TextAnalyzer;

/**
 * Interface for analyzing highlight
 *
 * @author Rose
 */
public interface CodeAnalyzer {

    /**
     * Analyze spans for the given input
     *
     * @param content  The input text
     * @param result   Result dest
     * @param delegate Delegate between thread and analyzer
     * @see TextAnalyzer#analyze(Content)
     * @see TextAnalyzer.AnalyzeThread.Delegate#shouldAnalyze()
     */
    void analyze(CharSequence content, TextAnalyzeResult result, TextAnalyzer.AnalyzeThread.Delegate delegate);

}
