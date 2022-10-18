package org.gradle.tooling.provider.model.internal;

import java.lang.annotation.*;

/**
 * Indicates that a given marker interface should be mixed in to instances of the annotated type before they
 * are passed to the tooling API client.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface LegacyConsumerInterface {
    String value();
}
