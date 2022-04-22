package com.tyron.builder.internal.exceptions;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.util.internal.TreeVisitor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class ContextAwareException extends BuildException {

    public ContextAwareException(Throwable t) {
        initCause(t);
    }

    /**
     * Returns the reportable causes for this failure.
     *
     * @return The causes. Never returns null, returns an empty list if this exception has no reportable causes.
     */
    public List<Throwable> getReportableCauses() {
        final List<Throwable> causes = new ArrayList<>();
        visitCauses(getCause(), new TreeVisitor<Throwable>() {
            @Override
            public void node(Throwable node) {
                causes.add(node);
            }
        });
        return causes;
    }

    public void accept(ExceptionContextVisitor contextVisitor) {
        Throwable cause = getCause();
        if (cause != null) {
            contextVisitor.visitCause(cause);
            visitCauses(cause, contextVisitor);
        }
    }

    private void visitCauses(Throwable t, TreeVisitor<? super Throwable> visitor) {
        if (t instanceof MultiCauseException) {
            MultiCauseException multiCauseException = (MultiCauseException) t;
            List<? extends Throwable> causes = multiCauseException.getCauses();
            if (!causes.isEmpty()) {
                visitor.startChildren();
                for (Throwable cause : causes) {
                    visitContextual(cause, visitor);
                }
                visitor.endChildren();
            }
        } else if (t.getCause() != null) {
            visitor.startChildren();
            visitContextual(t.getCause(), visitor);
            visitor.endChildren();
        }
    }

    private void visitContextual(Throwable t, TreeVisitor<? super Throwable> visitor) {
        Throwable next = findNearestContextual(t);
        if (next != null) {
            // Show any contextual cause recursively
            visitor.node(next);
            visitCauses(next, visitor);
        } else {
            // Show the direct cause of the last contextual cause only
            visitor.node(t);
        }
    }

    @Nullable
    private Throwable findNearestContextual(@Nullable Throwable t) {
        if (t == null) {
            return null;
        }
        if (t.getClass().getAnnotation(Contextual.class) != null || t instanceof MultiCauseException) {
            return t;
        }
        return findNearestContextual(t.getCause());
    }
}

