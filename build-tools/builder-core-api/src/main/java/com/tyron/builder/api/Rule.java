package com.tyron.builder.api;

/**
 * <p>A {@code Rule} represents some action to perform when an unknown domain object is referenced. The rule can use the
 * domain object name to add an implicit domain object.</p>
 */
public interface Rule {
    /**
     * Returns the description of the rule. This is used for reporting purposes.
     *
     * @return the description. should not return null.
     */
    String getDescription();

    /**
     * Applies this rule for the given unknown domain object. The rule can choose to ignore this name, or add a domain
     * object with the given name.
     *
     * @param domainObjectName The name of the unknown domain object.
     */
    void apply(String domainObjectName);
}
