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
package io.github.rosemoe.sora2.text;

import android.annotation.SuppressLint;
import android.util.Log;

import io.github.rosemoe.sora2.interfaces.EditorLanguage;
import io.github.rosemoe.sora2.util.IntPair;

import static io.github.rosemoe.sora2.text.TextUtils.isEmoji;

import androidx.annotation.IntDef;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author Rose
 * Warning:The cursor position will update automatically when the content has been changed by other way
 */
public final class Cursor {

    @IntDef(value = {Direction.LEFT, Direction.TOP, Direction.RIGHT, Direction.BOTTOM})
    public @interface Direction {
        int LEFT = 0;
        int TOP = 1;
        int RIGHT = 2;
        int BOTTOM = 3;
    }
    /**
     * Delegate for listening to different cursor events
     */
    public interface CursorMoveDelegate {
        /**
         * @param direction the
         */
        void onCursorMove(@Direction int direction);
    }

    private final Content mContent;
    private final CachedIndexer mIndexer;
    private CharPosition mLeft, mRight;
    private CharPosition cache0, cache1, cache2;
    private boolean mAutoIndentEnabled;
    private EditorLanguage mLanguage;
    private int mTabWidth;
    private final Set<Character> mIgnoredCharacters;

    private CursorMoveDelegate mCursorMoveDelegate;

    /**
     * Create a new Cursor for Content
     *
     * @param content Target content
     */
    public Cursor(Content content) {
        mContent = content;
        mIndexer = new CachedIndexer(content);
        mLeft = new CharPosition().zero();
        mRight = new CharPosition().zero();
        mTabWidth = 4;
        mIgnoredCharacters = new HashSet<>();

        Collections.addAll(mIgnoredCharacters, ')', '"', ']', '>');
    }

    public void setCursorMoveDelegate(CursorMoveDelegate delegate) {
        mCursorMoveDelegate = delegate;
    }

    /**
     * Whether the given character is a white space character
     *
     * @param c Character to check
     * @return Result whether a space char
     */
    protected static boolean isWhitespace(char c) {
        return (c == '\t' || c == ' ');
    }

    /**
     * Make left and right cursor on the given position
     *
     * @param line   The line position
     * @param column The column position
     */
    public void set(int line, int column) {
        setLeft(line, column);
        setRight(line, column);
    }

    /**
     * Make left cursor on the given position
     *
     * @param line   The line position
     * @param column The column position
     */
    public void setLeft(int line, int column) {
        mLeft = mIndexer.getCharPosition(line, column).fromThis();
    }

    /**
     * Make right cursor on the given position
     *
     * @param line   The line position
     * @param column The column position
     */
    public void setRight(int line, int column) {
        mRight = mIndexer.getCharPosition(line, column).fromThis();
    }

    /**
     * Get the left cursor line
     *
     * @return line of left cursor
     */
    public int getLeftLine() {
        return mLeft.getLine();
    }

    /**
     * Get the left cursor column
     *
     * @return column of left cursor
     */
    public int getLeftColumn() {
        return mLeft.getColumn();
    }

    /**
     * Get the right cursor line
     *
     * @return line of right cursor
     */
    public int getRightLine() {
        return mRight.getLine();
    }

    /**
     * Get the right cursor column
     *
     * @return column of right cursor
     */
    public int getRightColumn() {
        return mRight.getColumn();
    }

    /**
     * Whether the given position is in selected region
     *
     * @param line   The line to query
     * @param column The column to query
     * @return Whether is in selected region
     */
    public boolean isInSelectedRegion(int line, int column) {
        if (line >= getLeftLine() && line <= getRightLine()) {
            boolean yes = true;
            if (line == getLeftLine()) {
                yes = column >= getLeftColumn();
            }
            if (line == getRightLine()) {
                yes = yes && column < getRightColumn();
            }
            return yes;
        }
        return false;
    }

    /**
     * Get the left cursor index
     *
     * @return index of left cursor
     */
    public int getLeft() {
        return mLeft.index;
    }

    /**
     * Get the right cursor index
     *
     * @return index of right cursor
     */
    public int getRight() {
        return mRight.index;
    }

    /**
     * Notify the Indexer to update its cache for current display position
     * <p>
     * This will make querying actions quicker
     * <p>
     * Especially when the editor user want to set a new cursor position after scrolling long time
     *
     * @param line First visible line
     */
    public void updateCache(int line) {
        mIndexer.getCharIndex(line, 0);
    }

    /**
     * Get the using Indexer object
     *
     * @return Using Indexer
     */
    public CachedIndexer getIndexer() {
        return mIndexer;
    }

    /**
     * Get whether text is selected
     *
     * @return Whether selected
     */
    public boolean isSelected() {
        return mLeft.index != mRight.index;
    }

    /**
     * Returns whether auto indent is enabled
     *
     * @return Enabled or disabled
     */
    public boolean isAutoIndent() {
        return mAutoIndentEnabled;
    }

    /**
     * Enable or disable auto indent when insert text through Cursor
     *
     * @param enabled Auto Indent state
     */
    public void setAutoIndent(boolean enabled) {
        mAutoIndentEnabled = enabled;
    }

    /**
     * Set language for auto indent
     *
     * @param lang The target language
     */
    public void setLanguage(EditorLanguage lang) {
        mLanguage = lang;
    }

    /**
     * Set tab width for auto indent
     *
     * @param width tab width
     */
    public void setTabWidth(int width) {
        mTabWidth = width;
    }

    public void onCommitText(CharSequence text) {
        onCommitText(text, true);
    }

    /**
     * Commit text at current state
     *
     * @param text Text commit by InputConnection
     */
    public void onCommitText(CharSequence text, boolean applyAutoIndent) {
        if (isSelected()) {
            mContent.replace(getLeftLine(), getLeftColumn(), getRightLine(), getRightColumn(), text);
        } else {
            if (text.length() == 1
                    && mIgnoredCharacters.contains(text.charAt(0))
                    && text.charAt(0) == mContent.charAt(getLeft())
                    && mCursorMoveDelegate != null
                    && getLeftColumn() < mContent.getColumnCount(getLeftLine())) {
                char c = text.charAt(0);
                if (mIgnoredCharacters.contains(c)) {
                    mCursorMoveDelegate.onCursorMove(Direction.RIGHT);
                }
            } else {
                if (mAutoIndentEnabled && text.length() != 0 && applyAutoIndent) {
                    char first = text.charAt(0);
                    if (first == '\n') {
                        String line = mContent.getLineString(getLeftLine());
                        int p = 0, count = 0;
                        while (p < getLeftColumn()) {
                            if (isWhitespace(line.charAt(p))) {
                                if (line.charAt(p) == '\t') {
                                    count += mTabWidth;
                                } else {
                                    count++;
                                }
                                p++;
                            } else {
                                break;
                            }
                        }
                        String sub = line.substring(0, getLeftColumn());
                        try {
                            count += mLanguage.getIndentAdvance(sub);
                        } catch (Exception e) {
                            Log.w("EditorCursor", "Language object error", e);
                        }
                        StringBuilder sb = new StringBuilder(text);
                        sb.insert(1, createIndent(count));
                        text = sb;
                    }
                }
                mContent.insert(getLeftLine(), getLeftColumn(), text);
            }
        }
    }

    /**
     * Insert/commit multiline text with respect to current indentation
     *
     * @param text Multiline text to insert
     */
    @SuppressLint("NewApi")
    public void onCommitMultilineText(String text) {
        if (isSelected()) return;

        if (text == null || text.isEmpty()) return;

        if (!text.contains("\n")) {
            onCommitText(text);
            return;
        }

        String currentLine = mContent.getLine(getLeftLine()).toString();
        String currentIndent = currentLine.trim().isEmpty()
                ? currentLine // for the case where the whole line is just whitespace(s)
                : currentLine.substring(0, currentLine.indexOf(currentLine.trim()));

        String textToInsert = Arrays.stream(text.split("\\n"))
                .map(s -> currentIndent + s)
                .collect(Collectors.joining("\n"))
                .substring(currentIndent.length()); // delete the extra indent on the first line that we insert the text

        mContent.insert(getLeftLine(), getLeftColumn(), textToInsert);
    }

    /**
     * Create indent space
     *
     * @param p Target width effect
     * @return Generated space string
     */
    private String createIndent(int p) {
        int tab = 0;
        int space;
        if (mLanguage.useTab()) {
            tab = p / mTabWidth;
            space = p % mTabWidth;
        } else {
            space = p;
        }
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < tab; i++) {
            s.append('\t');
        }
        for (int i = 0; i < space; i++) {
            s.append(' ');
        }
        return s.toString();
    }

    /**
     * Handle delete submit by InputConnection
     */
    public void onDeleteKeyPressed() {
        if (isSelected()) {
            mContent.delete(getLeftLine(), getLeftColumn(), getRightLine(), getRightColumn());
        } else {
            int col = getLeftColumn(), len = 1;
            //Do not put cursor inside a emotion character
            if (col > 1) {
                char before = mContent.charAt(getLeftLine(), col - 2);
                if (isEmoji(before)) {
                    len = 2;
                }
            }
            mContent.delete(getLeftLine(), getLeftColumn() - len, getLeftLine(), getLeftColumn());
        }
    }

    /**
     * Get position after moving left once
     * @param position A packed pair (line, column) describing the original position
     * @return A packed pair (line, column) describing the result position
     */
    public long getLeftOf(long position) {
        int line = IntPair.getFirst(position);
        int column = IntPair.getSecond(position);
        if (column - 1 >= 0) {
            if (column - 2 >= 0) {
                char ch = mContent.charAt(line, column - 2);
                if (isEmoji(ch)) {
                    column--;
                }
            }
            return IntPair.pack(line, column - 1);
        } else {
            if (line == 0) {
                return 0;
            } else {
                int c_column = mContent.getColumnCount(line - 1);
                return IntPair.pack(line - 1, c_column);
            }
        }
    }

    /**
     * Get position after moving right once
     * @param position A packed pair (line, column) describing the original position
     * @return A packed pair (line, column) describing the result position
     */
    public long getRightOf(long position) {
        int line = IntPair.getFirst(position);
        int column = IntPair.getSecond(position);
        int c_column = mContent.getColumnCount(line);
        if (column + 1 <= c_column) {
            char ch = mContent.charAt(line, column);
            if (isEmoji(ch)) {
                column++;
                if (column + 1 > c_column) {
                    column--;
                }
            }
           return IntPair.pack(line, column + 1);
        } else {
            if (line + 1 == mContent.getLineCount()) {
                return IntPair.pack(line, c_column);
            } else {
                return IntPair.pack(line + 1, 0);
            }
        }
    }

    /**
     * Get position after moving up once
     * @param position A packed pair (line, column) describing the original position
     * @return A packed pair (line, column) describing the result position
     */
    public long getUpOf(long position) {
        int line = IntPair.getFirst(position);
        int column = IntPair.getSecond(position);
        if (line - 1 < 0) {
            line = 1;
        }
        int c_column = mContent.getColumnCount(line - 1);
        if (column > c_column) {
            column = c_column;
        }
        return IntPair.pack(line - 1, column);
    }

    /**
     * Get position after moving down once
     * @param position A packed pair (line, column) describing the original position
     * @return A packed pair (line, column) describing the result position
     */
    public long getDownOf(long position) {
        int line = IntPair.getFirst(position);
        int column = IntPair.getSecond(position);
        int c_line = mContent.getLineCount();
        if (line + 1 >= c_line) {
            return IntPair.pack(line, mContent.getColumnCount(line));
        } else {
            int c_column = mContent.getColumnCount(line + 1);
            if (column > c_column) {
                column = c_column;
            }
            return IntPair.pack(line + 1, column);
        }
    }

    /**
     * Get copy of left cursor
     */
    public CharPosition left() {
        return mLeft.fromThis();
    }

    /**
     * Get copy of right cursor
     */
    public CharPosition right() {
        return mRight.fromThis();
    }

    /**
     * Internal call back before insertion
     *
     * @param startLine   Start line
     * @param startColumn Start column
     */
    void beforeInsert(int startLine, int startColumn) {
        cache0 = mIndexer.getCharPosition(startLine, startColumn).fromThis();
    }

    /**
     * Internal call back before deletion
     *
     * @param startLine   Start line
     * @param startColumn Start column
     * @param endLine     End line
     * @param endColumn   End column
     */
    void beforeDelete(int startLine, int startColumn, int endLine, int endColumn) {
        cache1 = mIndexer.getCharPosition(startLine, startColumn).fromThis();
        cache2 = mIndexer.getCharPosition(endLine, endColumn).fromThis();
    }

    /**
     * Internal call back before replace
     */
    void beforeReplace() {
        mIndexer.beforeReplace(mContent);
    }

    /**
     * Internal call back after insertion
     *
     * @param startLine       Start line
     * @param startColumn     Start column
     * @param endLine         End line
     * @param endColumn       End column
     * @param insertedContent Inserted content
     */
    void afterInsert(int startLine, int startColumn, int endLine, int endColumn,
                     CharSequence insertedContent) {
        mIndexer.afterInsert(mContent, startLine, startColumn, endLine, endColumn, insertedContent);
        int beginIdx = cache0.getIndex();
        if (getLeft() >= beginIdx) {
            mLeft = mIndexer.getCharPosition(getLeft() + insertedContent.length()).fromThis();
        }
        if (getRight() >= beginIdx) {
            mRight = mIndexer.getCharPosition(getRight() + insertedContent.length()).fromThis();
        }
    }

    /**
     * Internal call back
     *
     * @param startLine      Start line
     * @param startColumn    Start column
     * @param endLine        End line
     * @param endColumn      End column
     * @param deletedContent Deleted content
     */
    void afterDelete(int startLine, int startColumn, int endLine, int endColumn,
                     CharSequence deletedContent) {
        mIndexer.afterDelete(mContent, startLine, startColumn, endLine, endColumn, deletedContent);
        int beginIdx = cache1.getIndex();
        int endIdx = cache2.getIndex();
        int left = getLeft();
        int right = getRight();
        if (beginIdx > right) {
            return;
        }
        if (endIdx <= left) {
            mLeft = mIndexer.getCharPosition(left - (endIdx - beginIdx)).fromThis();
            mRight = mIndexer.getCharPosition(right - (endIdx - beginIdx)).fromThis();
        } else if (/* endIdx > left && */ endIdx < right) {
            if (beginIdx <= left) {
                mLeft = mIndexer.getCharPosition(beginIdx).fromThis();
                mRight = mIndexer.getCharPosition(right - (endIdx - left)).fromThis();
            } else {
                mRight = mIndexer.getCharPosition(right - (endIdx - beginIdx)).fromThis();
            }
        } else {
            if (beginIdx <= left) {
                mLeft = mIndexer.getCharPosition(beginIdx).fromThis();
                mRight = mLeft.fromThis();
            } else {
                mRight = mIndexer.getCharPosition(left + (right - beginIdx)).fromThis();
            }
        }
    }

}

