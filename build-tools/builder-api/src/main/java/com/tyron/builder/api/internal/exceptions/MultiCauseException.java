package com.tyron.builder.api.internal.exceptions;

import java.util.List;

public interface MultiCauseException {
    List<? extends Throwable> getCauses();
}