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
package io.github.rosemoe.sora.graphics;

import android.graphics.Paint;

import java.util.Arrays;

import io.github.rosemoe.sora.text.TextUtils;

/**
 * Cache to measure text quickly,which is very useful when text is long
 * <p>
 * It is not thread-safe.
 *
 * @author Rosemoe
 */
public class FontCache {

    private final float[] cache;

    private final char[] buffer;

    public FontCache() {
        cache = new float[65536];
        buffer = new char[3];
    }

    /**
     * Clear caches of font
     */
    public void clearCache() {
        Arrays.fill(cache, 0);
    }

    /**
     * Measure a single character
     */
    public float measureChar(char ch, Paint p) {
        float width = cache[ch];
        if (width == 0) {
            buffer[0] = ch;
            width = p.measureText(buffer, 0, 1);
            cache[ch] = width;
        }
        return width;
    }

    /*
     * Measure text
     */
    public float measureText(char[] chars, int start, int end, Paint p) {
        float width = 0f;
        for (int i = start; i < end; i++) {
            char ch = chars[i];
            if (TextUtils.isEmoji(ch) && i + 1 < end) {
                buffer[0] = ch;
                buffer[1] = chars[++i];
                if (i + 1 < end) {
                    buffer[2] = chars[++i];
                    if (!TextUtils.isEmoji(buffer[1]) || TextUtils.isEmoji(buffer[2])) {
                        i--;
                        width += p.measureText(buffer, 0, 2);
                    } else {
                        width += p.measureText(buffer, 0, 3);
                    }
                } else {
                    width += p.measureText(buffer, 0, 2);
                }
            } else {
                width += measureChar(ch, p);
            }
        }
        return width;
    }

    /**
     * Measure text
     */
    public float measureText(CharSequence str, int start, int end, Paint p) {
        float width = 0f;
        for (int i = start; i < end; i++) {
            char ch = str.charAt(i);
            if (TextUtils.isEmoji(ch) && i + 1 < end) {
                buffer[0] = ch;
                buffer[1] = str.charAt(++i);
                if (i + 1 < end) {
                    buffer[2] = str.charAt(++i);
                    if (!TextUtils.isEmoji(buffer[1]) || TextUtils.isEmoji(buffer[2])) {
                        buffer[2] = 0;
                        i--;
                        width += p.measureText(buffer, 0, 2);
                    } else {
                        width += p.measureText(buffer, 0, 3);
                    }
                } else {
                    width += p.measureText(buffer, 0, 2);
                }
            } else {
                width += measureChar(ch, p);
            }
        }
        return width;
    }

}
