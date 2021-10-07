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
package io.github.rosemoe.sora.langs;

import java.util.ArrayList;
import java.util.List;

import io.github.rosemoe.sora.interfaces.AutoCompleteProvider;
import io.github.rosemoe.sora.interfaces.CodeAnalyzer;
import io.github.rosemoe.sora.interfaces.EditorLanguage;
import io.github.rosemoe.sora.interfaces.NewlineHandler;
import io.github.rosemoe.sora.data.CompletionItem;
import io.github.rosemoe.sora.text.TextAnalyzeResult;
import io.github.rosemoe.sora.text.TextAnalyzer.AnalyzeThread.Delegate;
import io.github.rosemoe.sora.widget.SymbolPairMatch;

/**
 * Empty language
 *
 * @author Rosemoe
 */
public class EmptyLanguage implements EditorLanguage {

    @Override
    public CharSequence format(CharSequence text) {
        return text;
    }

    @Override
    public SymbolPairMatch getSymbolPairs() {
        return new SymbolPairMatch.DefaultSymbolPairs();
    }

    @Override
    public NewlineHandler[] getNewlineHandlers() {
        return new NewlineHandler[0];
    }

    @Override
    public CodeAnalyzer getAnalyzer() {
        return new EmptyCodeAnalyzer();
    }

    @Override
    public AutoCompleteProvider getAutoCompleteProvider() {
        return new EmptyAutoCompleteProvider();
    }

    @Override
    public boolean isAutoCompleteChar(char ch) {
        return false;
    }

    @Override
    public int getIndentAdvance(String content) {
        return 0;
    }

    @Override
    public boolean useTab() {
        return false;
    }

    public static class EmptyAutoCompleteProvider implements AutoCompleteProvider {

        @Override
        public List<CompletionItem> getAutoCompleteItems(String prefix, TextAnalyzeResult analyzeResult, int line, int column) {
            return new ArrayList<>();
        }

    }

    public static class EmptyCodeAnalyzer implements CodeAnalyzer {

        @Override
        public void analyze(CharSequence content, TextAnalyzeResult result, Delegate delegate) {
            result.addNormalIfNull();
        }

    }
}

