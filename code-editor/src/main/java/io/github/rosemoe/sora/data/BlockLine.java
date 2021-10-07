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
package io.github.rosemoe.sora.data;

/**
 * Code block info model
 *
 * @author Rose
 */
public class BlockLine {

    /**
     * Start line of code block
     */
    public int startLine;

    /**
     * Start column of code block
     */
    public int startColumn;

    /**
     * End line of code block
     */
    public int endLine;

    /**
     * End column of code block
     */
    public int endColumn;

    /**
     * Indicates that this BlockLine should be drawn vertically until the bottom of its end line
     */
    public boolean toBottomOfEndLine;

    public void clear() {
        startColumn = startLine = endLine = endColumn = 0;
        toBottomOfEndLine = false;
    }

}
