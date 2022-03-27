package com.tyron.builder.api.internal.typeconversion;

import com.tyron.builder.api.internal.exceptions.DiagnosticsVisitor;
import com.tyron.builder.api.internal.exceptions.FormattingDiagnosticsVisitor;

class ErrorHandlingNotationParser<N, T> implements NotationParser<N, T> {
    private final Object targetTypeDisplayName;
    private final String invalidNotationMessage;
    private final boolean allowNullInput;
    private final NotationParser<N, T> delegate;

    public ErrorHandlingNotationParser(Object targetTypeDisplayName, String invalidNotationMessage, boolean allowNullInput, NotationParser<N, T> delegate) {
        this.targetTypeDisplayName = targetTypeDisplayName;
        this.invalidNotationMessage = invalidNotationMessage;
        this.allowNullInput = allowNullInput;
        this.delegate = delegate;
    }

    @Override
    public void describe(DiagnosticsVisitor visitor) {
        delegate.describe(visitor);
    }

    @Override
    public T parseNotation(N notation) {
        String failure;
        //TODO SF add quotes to both formats (there will be *lots* of tests failing so I'm not sure if it is worth it).
        if (notation == null && !allowNullInput) {
            failure = String.format("Cannot convert a null value to %s.", targetTypeDisplayName);
        } else {
            try {
                return delegate.parseNotation(notation);
            } catch (UnsupportedNotationException e) {
                failure = String.format("Cannot convert the provided notation to %s: %s.", targetTypeDisplayName, e.getNotation());
            }
        }

        FormattingDiagnosticsVisitor visitor = new FormattingDiagnosticsVisitor();
        describe(visitor);

        throw new UnsupportedNotationException(notation, failure, invalidNotationMessage, visitor.getCandidates());
    }
}