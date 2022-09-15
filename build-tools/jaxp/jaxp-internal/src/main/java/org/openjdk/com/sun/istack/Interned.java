package org.openjdk.com.sun.istack;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

/**
 * Designates that a field, return value, argument, or a variable is supposed
 * to be an {@link String#intern() interned} string.
 *
 * <p>
 * In many places in the istack, we assume Strings to be interned for
 * the performance reason. Similarly, In many other places, we don't
 * make such an assumption for the performance reason (because intern
 * isn't free.)
 *
 * <p>
 * Therefore, distinguishing which part is supposed to be interned and
 * which part is supposed to be not is important. This annotation
 * allows us to capture that in the code.
 *
 * @author Kohsuke Kawaguchi
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.FIELD,ElementType.METHOD,ElementType.PARAMETER,ElementType.LOCAL_VARIABLE})
public @interface Interned {
}