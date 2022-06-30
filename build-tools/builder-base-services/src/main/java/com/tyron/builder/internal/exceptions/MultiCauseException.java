package com.tyron.builder.internal.exceptions;

import java.util.List;

public interface MultiCauseException {
    List<? extends Throwable> getCauses();
}