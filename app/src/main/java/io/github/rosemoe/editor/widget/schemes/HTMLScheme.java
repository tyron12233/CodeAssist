/*
 *   Copyright 2020-2021 Rosemoe
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package io.github.rosemoe.editor.widget.schemes;

import io.github.rosemoe.editor.widget.EditorColorScheme;

/**
 * ColorScheme for HTML Language for editor
 */
public class HTMLScheme extends EditorColorScheme {

    @Override
    public void applyDefault() {
        super.applyDefault();
        setColor(OPERATOR, 0xff4fc3f7);
        setColor(BLOCK_LINE, 0xff717171);
        setColor(BLOCK_LINE_CURRENT, 0xffffffff);
        setColor(NON_PRINTABLE_CHAR, 0xffdddddd);
        setColor(CURRENT_LINE, 0xff464646);
        setColor(SELECTION_INSERT, 0xffffffff);
        setColor(SELECTION_HANDLE, 0xffffffff);
        setColor(LINE_NUMBER, 0xff2b9eaf);
        setColor(LINE_DIVIDER, 0xff2b9eaf);
        setColor(LINE_NUMBER_BACKGROUND, 0xff1e1e1e);
        setColor(WHOLE_BACKGROUND, 0xff212121);
        setColor(ATTRIBUTE_VALUE, 0xff8bc34a);
        setColor(ATTRIBUTE_NAME, 0xff333333);
        setColor(HTML_TAG, 0xffff6060);
        setColor(TEXT_NORMAL, 0xffffffff);
        setColor(IDENTIFIER_NAME, 0xfff0be4b);
        setColor(COMMENT, 0xffbdbdbd);
    }

}
