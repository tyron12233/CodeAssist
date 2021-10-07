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

import android.content.res.TypedArray;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;

import io.github.rosemoe.sora.interfaces.EditorTextActionPresenter;
import io.github.rosemoe.sora.util.IntPair;

/**
 * Action Mode style text action panel for editor
 *
 * @author Rose
 */
class EditorTextActionModeStarter implements EditorTextActionPresenter {

    private final CodeEditor mEditor;
    private ActionMode mActionMode;

    EditorTextActionModeStarter(CodeEditor editor) {
        mEditor = editor;
    }

    @Override
    public void onBeginTextSelect() {
        if (mActionMode != null) {
            return;
        }
        mActionMode = mEditor.startActionMode(new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode actionMode, Menu menu) {
                mEditor.mStartedActionMode = CodeEditor.ACTION_MODE_SELECT_TEXT;
                actionMode.setTitle(android.R.string.selectTextMode);
                TypedArray array = mEditor.getContext().getTheme().obtainStyledAttributes(new int[]{
                        android.R.attr.actionModeSelectAllDrawable,
                        android.R.attr.actionModeCutDrawable,
                        android.R.attr.actionModeCopyDrawable,
                        android.R.attr.actionModePasteDrawable,
                });
                menu.add(0, 0, 0, mEditor.getContext().getString(android.R.string.selectAll))
                        .setShowAsActionFlags(2)
                        .setIcon(array.getDrawable(0));

                menu.add(0, 1, 0, mEditor.getContext().getString(android.R.string.cut))
                        .setShowAsActionFlags(2)
                        .setIcon(array.getDrawable(1));

                menu.add(0, 2, 0, mEditor.getContext().getString(android.R.string.copy))
                        .setShowAsActionFlags(2)
                        .setIcon(array.getDrawable(2));

                menu.add(0, 3, 0, mEditor.getContext().getString(android.R.string.paste))
                        .setShowAsActionFlags(2)
                        .setIcon(array.getDrawable(3));

                array.recycle();
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode actionMode, Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(ActionMode actionMode, MenuItem menuItem) {
                switch (menuItem.getItemId()) {
                    case 0:
                        mEditor.selectAll();
                        break;
                    case 1:
                        mEditor.cutText();
                        onExit();
                        break;
                    case 2:
                        mEditor.copyText();
                        onExit();
                        break;
                    case 3:
                        mEditor.pasteText();
                        onExit();
                }
                return false;
            }

            @Override
            public void onDestroyActionMode(ActionMode actionMode) {
                mEditor.mStartedActionMode = CodeEditor.ACTION_MODE_NONE;
                mActionMode = null;
                mEditor.setSelection(mEditor.getCursor().getLeftLine(), mEditor.getCursor().getLeftColumn());
            }

        });
    }

    @Override
    public void onUpdate() {

    }

    @Override
    public void onUpdate(int updateReason) {

    }

    @Override
    public void onSelectedTextClicked(MotionEvent event) {
        long packed = mEditor.getPointPositionOnScreen(event.getX(), event.getY());
        int line = IntPair.getFirst(packed);
        int column = IntPair.getSecond(packed);
        mEditor.setSelection(line, column);
        mEditor.hideAutoCompleteWindow();
    }

    @Override
    public void onTextSelectionEnd() {

    }

    @Override
    public boolean onExit() {
        if (mActionMode != null) {
            mActionMode.finish();
            mActionMode = null;
            return true;
        }
        return false;
    }

    @Override
    public boolean shouldShowCursor() {
        return true;
    }

}
