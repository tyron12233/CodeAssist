package com.tyron.builder.internal.service.scopes;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Attached to a listener interface to indicate that its events are stateful.
 *
 * <p>The listener infrastructure will ensure that a listener of this type will either receive all events, or no events.
 * Currently, this is done by disallowing the registration of a listener of this type once any events have been fired.
 *
 * @see Scopes
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
public @interface StatefulListener {
}