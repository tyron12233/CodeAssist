package com.tyron.builder.api.component;

/**
 * A software component factory is responsible for providing to
 * plugins a way to create software components. Currently the
 * software factory only allows the creation of adhoc software
 * components which can be used for publishing simple components.
 *
 * This is the case whenever there's no component model to be
 * used and that the plugin can solely rely on outgoing variants
 * to publish variants.
 *
 * @since 5.3
 */
public interface SoftwareComponentFactory {
    /**
     * Creates an adhoc software component, which can be used by plugins to
     * build custom component types.
     *
     * @param name the name of the component
     * @return the created component, which must be added to the components container later
     *
     * @since 5.3
     */
    AdhocComponentWithVariants adhoc(String name);
}
