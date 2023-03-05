package org.gradle.internal.exceptions;

import java.util.List;

public interface MultiCauseException {
    List<? extends Throwable> getCauses();
}