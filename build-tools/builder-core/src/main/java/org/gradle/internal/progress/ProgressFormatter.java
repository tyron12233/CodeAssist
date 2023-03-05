package org.gradle.internal.progress;

interface ProgressFormatter {
    String incrementAndGetProgress();
    String getProgress();
}
