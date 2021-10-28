package com.tyron.psi.patterns;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

/**
 * @author peter
 */
public abstract class InitialPatternCondition<T> {
    private final Class<T> myAcceptedClass;

    protected InitialPatternCondition(@NotNull Class<T> aAcceptedClass) {
        myAcceptedClass = aAcceptedClass;
    }

    @NotNull
    public Class<T> getAcceptedClass() {
        return myAcceptedClass;
    }

    public boolean accepts(@Nullable Object o, final ProcessingContext context) {
        return myAcceptedClass.isInstance(o);
    }

    @NonNls
    public final String toString() {
        StringBuilder builder = new StringBuilder();
        append(builder, "");
        return builder.toString();
    }

    public void append(@NonNls @NotNull StringBuilder builder, final String indent) {
        builder.append("instanceOf(").append(myAcceptedClass.getSimpleName()).append(")");
    }
}