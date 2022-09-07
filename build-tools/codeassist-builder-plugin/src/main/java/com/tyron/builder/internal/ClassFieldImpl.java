package com.tyron.builder.internal;

import com.android.annotations.NonNull;
import com.android.annotations.concurrency.Immutable;
import com.tyron.builder.model.ClassField;
import com.google.common.collect.ImmutableSet;
import java.io.Serializable;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable implementation of {@link ClassField}
 */
@Immutable
public final class ClassFieldImpl implements ClassField, Serializable {

    private static final long serialVersionUID = 3645139904046297388L;

    @NonNull
    private final String type;
    @NonNull
    private final String name;
    @NonNull
    private final String value;
    @NonNull private final String documentation;
    @NonNull private final ImmutableSet<String> annotations;

    public ClassFieldImpl(@NonNull String type, @NonNull String name, @NonNull String value) {
        this(type, name, value, "", ImmutableSet.of());
    }

    public ClassFieldImpl(
            @NonNull String type,
            @NonNull String name,
            @NonNull String value,
            @NonNull String documentation,
            @NonNull ImmutableSet<String> annotations) {
        //noinspection ConstantConditions
        if (type == null
                || name == null
                || value == null
                || documentation == null
                || annotations == null) {
            throw new NullPointerException("Build Config field cannot have a null parameter");
        }
        this.type = type;
        this.name = name;
        this.value = value;
        this.documentation = documentation;
        this.annotations = annotations;
    }

    @Override
    @NonNull
    public String getType() {
        return type;
    }

    @Override
    @NonNull
    public String getName() {
        return name;
    }

    @Override
    @NonNull
    public String getValue() {
        return value;
    }

    @NonNull
    @Override
    public String getDocumentation() {
        return documentation;
    }

    @NonNull
    @Override
    public Set<String> getAnnotations() {
        return annotations;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ClassFieldImpl that = (ClassFieldImpl) o;

        return type.equals(that.type)
                && name.equals(that.name)
                && value.equals(that.value)
                && documentation.equals(that.documentation)
                && annotations.equals(that.annotations);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, name, value, documentation, annotations);
    }
}
