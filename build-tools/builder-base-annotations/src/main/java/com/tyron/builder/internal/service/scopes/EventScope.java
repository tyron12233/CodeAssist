package com.tyron.builder.internal.service.scopes;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Attached to a listener interface to indicate which scope its events are generated in.
 *
 * <p>Events generated in a particular scope are visible to listeners in the same scope and ancestor scopes.
 * Events are not visible to listeners in descendent scopes.
 *
 * <p>This annotation is used primarily to indicate to developers the scopes where this listener are available. There is also
 * some validation when a broadcaster or listener of this type is used in an incorrect scope.
 *
 * @see Scopes
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
public @interface EventScope {
    Class<? extends Scope> value();
}