package org.gradle.plugins.ide.internal.tooling.model;

import java.io.Serializable;
import java.util.Comparator;

/**
 * Compares task names to create ordering for selector launching.
 */
public class TaskNameComparator implements Comparator<String>, Serializable {
    @Override
    public int compare(String taskName1, String taskName2) {
        int depthDiff = getDepth(taskName1) - getDepth(taskName2);
        if (depthDiff != 0) {
            return depthDiff;
        }
        return compareSegments(taskName1, taskName2);
    }

    private int compareSegments(String taskName1, String taskName2) {
        int colon1 = taskName1.indexOf(':');
        int colon2 = taskName2.indexOf(':');
        if (colon1 > 0 && colon2 > 0) {
            int diff = taskName1.substring(0, colon1).compareTo(taskName2.substring(0, colon2));
            if (diff != 0) {
                return diff;
            }
        }
        return colon1 == -1 ? taskName1.compareTo(taskName2) : compareSegments(taskName1.substring(colon1 + 1), taskName2.substring(colon2 + 1));
    }

    private int getDepth(String taskName) {
        int counter = 0;
        for (char c : taskName.toCharArray()) {
            if (c == ':') {
                counter++;
            }
        }
        return counter;
    }
}