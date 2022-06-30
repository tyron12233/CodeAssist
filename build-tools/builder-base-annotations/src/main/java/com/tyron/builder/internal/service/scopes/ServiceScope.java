package com.tyron.builder.internal.service.scopes;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Attached to a service interface to indicate in which scope it is defined in.
 * Services are lifecycled with their scope, and stopped/closed when the scope is closed.
 *
 * Services are visible to other services in the same scope and descendent scopes.
 * Services are not visible to services in ancestor scopes.
 *
 * @see Scopes
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
public @interface ServiceScope {

    Class<? extends Scope> value();

}