package com.tyron.builder.internal.reflect;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.internal.reflect.validation.DefaultPropertyValidationProblemBuilder;
import com.tyron.builder.internal.reflect.validation.DefaultTypeValidationProblemBuilder;
import com.tyron.builder.internal.reflect.validation.PropertyProblemBuilder;
import com.tyron.builder.internal.reflect.validation.TypeProblemBuilder;
import com.tyron.builder.internal.reflect.validation.TypeValidationContext;
import com.tyron.builder.internal.reflect.validation.TypeValidationProblem;
import com.tyron.builder.plugin.use.PluginId;

import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.function.Supplier;

abstract public class ProblemRecordingTypeValidationContext implements TypeValidationContext {
    private final DocumentationRegistry documentationRegistry;
    private final Class<?> rootType;
    private final Supplier<Optional<PluginId>> pluginId;

    public ProblemRecordingTypeValidationContext(
            DocumentationRegistry documentationRegistry,
            @Nullable Class<?> rootType,
            Supplier<Optional<PluginId>> pluginId
    ) {
        this.documentationRegistry = documentationRegistry;
        this.rootType = rootType;
        this.pluginId = pluginId;
    }

    @Override
    public void visitTypeProblem(Action<? super TypeProblemBuilder> problemSpec) {
        DefaultTypeValidationProblemBuilder
                builder = new DefaultTypeValidationProblemBuilder(documentationRegistry, pluginId());
        problemSpec.execute(builder);
        recordProblem(builder.build());
    }

    @Nullable
    private PluginId pluginId() {
        return pluginId.get().orElse(null);
    }

    @Override
    public void visitPropertyProblem(Action<? super PropertyProblemBuilder> problemSpec) {
        DefaultPropertyValidationProblemBuilder builder = new DefaultPropertyValidationProblemBuilder(documentationRegistry, pluginId());
        problemSpec.execute(builder);
        builder.forType(rootType);
        recordProblem(builder.build());
    }

    abstract protected void recordProblem(TypeValidationProblem problem);
}