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

import android.view.MotionEvent;

import io.github.rosemoe.sora.widget.TextComposeBasePopup;

/**
 * Interface for various ways to present text action panel
 */
public interface EditorTextActionPresenter {

    /**
     * Selected text is clicked
     *
     * @param event Event
     */
    void onSelectedTextClicked(MotionEvent event);

    /**
     * Text selection, gesture interaction is over
     */
    void onTextSelectionEnd();

    /**
     * Notify that the position of panel should be updated.
     * If the presenter is displayed in editor's viewport, it should update
     * its position
     */
    void onUpdate();

    /**
     * Notify that the position of panel should be updated.
     * If the presenter is displayed in editor's viewport, it should update
     * its position
     *
     * @param updateReason {@link TextComposeBasePopup#DISMISS} {@link TextComposeBasePopup#DRAG} {@link TextComposeBasePopup#SCROLL}
     */
    void onUpdate(int updateReason);

    /**
     * Start the presenter
     */
    void onBeginTextSelect();


    /**
     * Exit the presenter
     *
     * @return Whether action is executed. Return true if this has cause UI change such as
     * popup window hides and action mode exits
     */
    boolean onExit();

    /**
     * Called by editor to check whether it should draw handles of cursor
     */
    boolean shouldShowCursor();

}
