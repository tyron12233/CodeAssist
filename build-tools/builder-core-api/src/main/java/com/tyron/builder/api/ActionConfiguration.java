package com.tyron.builder.api;

/**
 * Allows specification of configuration for some action.
 *
 * <p>The configuration is represented using zero or more initialization parameters to use when constructing an instance of the implementation class. The following types are supported:</p>
 *
 * <ul>
 *     <li>{@link String}</li>
 *     <li>{@link Boolean}</li>
 *     <li>{@link Integer}, {@link Long}, {@link Short} and other {@link Number} subtypes.</li>
 *     <li>{@link java.io.File}</li>
 *     <li>A {@link java.util.List} or {@link java.util.Set} of any supported type.</li>
 *     <li>An array of any supported type.</li>
 *     <li>A {@link java.util.Map} with keys and values of any supported type.</li>
 *     <li>An {@link Enum} type.</li>
 *     <li>A {@link Named} type created using {@link com.tyron.builder.api.model.ObjectFactory#named(Class, String)}.</li>
 *     <li>Any serializable type.</li>
 * </ul>
 *
 * @since 4.0
 */
public interface ActionConfiguration {
    /**
     * Adds initialization parameters to use when constructing an instance of the implementation class.
     *
     * @param params - the parameters to use during construction
     */
    void params(Object... params);

    /**
     * Sets any initialization parameters to use when constructing an instance of the implementation class.
     *
     * @param params - the parameters to use during construction
     */
    void setParams(Object... params);

    /**
     * Gets the initialization parameters that will be used when constructing an instance of the implementation class.
     *
     * @return the parameters to use during construction
     */
    Object[] getParams();
}
