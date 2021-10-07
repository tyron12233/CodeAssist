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

/**
 * Perform text processing when user enters '\n' and selection size is 0
 */
public interface NewlineHandler {

    /**
     * Checks whether the given input matches the requirement to invoke this handler
     *
     * @param beforeText Text of line before cursor
     * @param afterText  Text of line after cursor
     * @return Whether this handler should be called
     */
    boolean matchesRequirement(String beforeText, String afterText);

    /**
     * Handle newline and return processed content to insert
     *
     * @param beforeText Text of line before cursor
     * @param afterText  Text of line after cursor
     * @return Actual content to insert
     */
    HandleResult handleNewline(String beforeText, String afterText, int tabSize);

    class HandleResult {

        /**
         * Text to insert
         */
        public final CharSequence text;

        /**
         * Count to shift left from the end of {@link HandleResult#text}
         */
        public final int shiftLeft;

        public HandleResult(CharSequence text, int shiftLeft) {
            this.text = text;
            this.shiftLeft = shiftLeft;
            if (shiftLeft < 0 || shiftLeft > text.length()) {
                throw new IllegalArgumentException("invalid shiftLeft");
            }
        }

    }

}
