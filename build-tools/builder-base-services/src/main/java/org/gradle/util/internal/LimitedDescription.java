package org.gradle.util.internal;

import com.google.common.collect.Lists;

import java.util.LinkedList;
import java.util.List;

/**
 * Discards old entries when current count is over the limit.
 */
public class LimitedDescription {

    private final LinkedList<String> content;
    private final int maxItems;

    public LimitedDescription(int maxItems) {
        this.maxItems = maxItems;
        this.content = new LinkedList<String>();
    }

    public LimitedDescription append(String line) {
        content.add(0, line);
        if (content.size() > maxItems) {
            content.removeLast();
        }
        return this;
    }

    public String toString() {
        if (content.size() == 0) {
            return "<<empty>>";
        }

        StringBuilder out = new StringBuilder();
        List<String> reversed = Lists.reverse(content);
        for (Object item : reversed) {
            out.append(item).append("\n");
        }

        return out.toString();
    }
}
