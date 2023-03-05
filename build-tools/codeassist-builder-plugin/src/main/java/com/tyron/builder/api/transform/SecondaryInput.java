package com.tyron.builder.api.transform;

/**
 * Represents a change event for a {@link SecondaryFile} transform input.
 * @deprecated
 */
@Deprecated
public interface SecondaryInput {

    /**
     * The change event subject.
     * @return an instance of {@link SecondaryFile} that represent a transform input file.
     */
    SecondaryFile getSecondaryInput();

    /**
     * The change status.
     * @return the change status.
     */
    Status getStatus();
}
