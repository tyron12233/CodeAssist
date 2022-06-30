package com.tyron.builder.internal.compiler.java.listeners.constants;

import java.util.function.BiConsumer;

public class ConstantDependentsConsumer {

    private final BiConsumer<String, String> accessibleDependentDelegate;
    private final BiConsumer<String, String> privateDependentDelegate;

    public ConstantDependentsConsumer(BiConsumer<String, String> accessibleDependentConsumer, BiConsumer<String, String> privateDependentConsumer) {
        this.accessibleDependentDelegate = accessibleDependentConsumer;
        this.privateDependentDelegate = privateDependentConsumer;
    }

    /**
     * Consume "accessible" dependents of a constant. Accessible dependents in this context
     * are dependents that have a constant calculated from constant from origin.
     *
     * Example of accessible dependent:
     * class A {
     *     public static final int CALCULATE_ACCESSIBLE_CONSTANT = CONSTANT;
     * }
     */
    public void consumeAccessibleDependent(String constantOrigin, String constantDependent) {
        accessibleDependentDelegate.accept(constantOrigin, constantDependent);
    }

    /**
     * Consume "private" dependents of a constant.
     *
     * Example of private constant dependent:
     * class A {
     *     public static int method() {
     *         return CONSTANT;
     *     }
     * }
     */
    public void consumePrivateDependent(String constantOrigin, String constantDependent) {
        privateDependentDelegate.accept(constantOrigin, constantDependent);
    }

}
