package com.tyron.builder.problems;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public class BaseSolution implements Solution {
    private final Supplier<String> shortDescription;
    private final Supplier<String> longDescription;
    private final Supplier<String> documentationLink;

    public BaseSolution(Supplier<String> shortDescription,
                        Supplier<String> longDescription,
                        Supplier<String> documentationLink) {
        this.shortDescription = Objects.requireNonNull(shortDescription, "short description supplier must not be null");
        this.longDescription = Objects.requireNonNull(longDescription, "long description supplier must not be null");
        this.documentationLink = Objects.requireNonNull(documentationLink, "documentation link supplier must not be null");
    }

    @Override
    public String getShortDescription() {
        return shortDescription.get();
    }

    @Override
    public Optional<String> getLongDescription() {
        return Optional.ofNullable(longDescription.get());
    }

    @Override
    public Optional<String> getDocumentationLink() {
        return Optional.ofNullable(documentationLink.get());
    }
}