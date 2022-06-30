package com.tyron.builder.api.resources;

import java.net.URI;

/**
 * A generic resource of some kind. Only describes the resource.
 * There are more specific interface that extend this one and specify ways of accessing the resource's content.
 */
public interface Resource {

    /**
     * Human readable name of this resource
     *
     * @return human readable name, should not be null
     */
    String getDisplayName();

    /**
     * Uniform resource identifier that uniquely describes this resource
     *
     * @return unique URI, should not be null
     */
    URI getURI();

    /**
     * Short name that concisely describes this resource
     *
     * @return concise base name, should not be null
     */
    String getBaseName();
}