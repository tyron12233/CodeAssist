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

import java.util.List;

/**
 * A object provider for speed improvement
 * Now meaningless because it is not as well as it expected
 *
 * @author Rose
 */
public class ObjectAllocator {

    private static final int RECYCLE_LIMIT = 1024 * 8;
    private static List<BlockLine> blockLines;

    public static void recycleBlockLine(List<BlockLine> src) {
        if (src == null) {
            return;
        }
        if (blockLines == null) {
            blockLines = src;
            return;
        }
        int size = blockLines.size();
        int sizeAnother = src.size();
        while (sizeAnother > 0 && size < RECYCLE_LIMIT) {
            size++;
            sizeAnother--;
            var obj = src.get(sizeAnother);
            obj.clear();
            blockLines.add(obj);
        }
    }

    public static BlockLine obtainBlockLine() {
        return (blockLines == null || blockLines.isEmpty()) ? new BlockLine() : blockLines.remove(blockLines.size() - 1);
    }

}
