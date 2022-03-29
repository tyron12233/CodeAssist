package com.tyron.builder.api.internal.instantiation.generator;

/**
 * Encapsulates the differences, due to backwards compatibility, in instantiation for several different types.
 */
interface ConstructorSelector {
    /**
     * Allows this selector to veto the parameters to use with the given constructor.
     */
    void vetoParameters(ClassGenerator.GeneratedConstructor<?> constructor, Object[] parameters);

    /**
     * Locates the constructor that <em>should</em> be used to create instances of the given type with the given params.
     *
     * <p>The selected constructor does not have to accept the given parameters. It is the caller's responsibility to verify that the constructor can be
     * called with the given parameters.
     *
     * <p>The selector may or may not allow null parameters. The caller should allow null parameters and delegate to the selector to make this decision.
     *
     * <p>The selector may or may not allow instances of non-static inner classes to be created.</p>
     */
    <T> ClassGenerator.GeneratedConstructor<? extends T> forParams(Class<T> type, Object[] params);

    /**
     * Locates the constructor that <em>should</em> be used to create instances of the given type.
     *
     * @throws UnsupportedOperationException When this selector requires the parameters in order to select a constructor.
     */
    <T> ClassGenerator.GeneratedConstructor<? extends T> forType(Class<T> type) throws UnsupportedOperationException;
}
