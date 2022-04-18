package com.tyron.builder.internal.serialize;

import java.io.Serializable;

class NestedExceptionPlaceholder implements Serializable {
    private static final long serialVersionUID = 1L;
    private final Kind kind;
    private final int index;

    NestedExceptionPlaceholder(Kind kind, int index) {
        this.kind = kind;
        this.index = index;
    }

    public Kind getKind() {
        return kind;
    }

    public int getIndex() {
        return index;
    }

    enum Kind {
        cause,
        suppressed
    }
}