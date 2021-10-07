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

import java.util.HashMap;
import java.util.Map;

/**
 * Define symbol pairs to complete them automatically when the user
 * enters the first character of pair.
 *
 * @author Rosemoe
 */
public class SymbolPairMatch {

    private Map<Character, Replacement> pairMaps = new HashMap<>();

    private SymbolPairMatch parent;

    public SymbolPairMatch() {
        this(null);
    }

    public SymbolPairMatch(SymbolPairMatch parent) {
        setParent(parent);
    }

    protected void setParent(SymbolPairMatch parent) {
        this.parent = parent;
    }

    /**
     * Put a pair of symbol completion
     * When the user types the {@param firstCharacter}, it will be replaced by {@param replacement}
     * Replacement maybe null to disable completion for this character.
     *
     * @see Replacement
     */
    public void putPair(char firstCharacter, Replacement replacement) {
        pairMaps.put(firstCharacter, replacement);
    }

    public final Replacement getCompletion(char character) {
        Replacement result = parent != null ? parent.getCompletion(character) : null;
        if (result == null) {
            result = pairMaps.get(character);
        }
        return result;
    }

    public void removeAllRules() {
        pairMaps.clear();
    }

    /**
     * Defines a replacement of input
     */
    public static class Replacement {

        /**
         * Defines that this character does not have to be replaced
         */
        public final static Replacement NO_REPLACEMENT = new Replacement("", 0);

        public final String text;

        public final int selection;

        /**
         * The entered character will be replaced to {@param text} and
         * the new cursor position will be {@param selection}
         * The value of {@param selection} maybe 0 to {@param text}.length()
         */
        public Replacement(String text, int selection) {
            this.selection = selection;
            this.text = text;
            if (selection < 0 || selection > text.length()) {
                throw new IllegalArgumentException("invalid selection value");
            }
        }

    }

    public final static class DefaultSymbolPairs extends SymbolPairMatch {

        public DefaultSymbolPairs() {
            super.putPair('{', new Replacement("{}", 1));
            super.putPair('(', new Replacement("()", 1));
            super.putPair('[', new Replacement("[]", 1));
            super.putPair('"', new Replacement("\"\"", 1));
            super.putPair('\'', new Replacement("''", 1));
        }

    }

}
