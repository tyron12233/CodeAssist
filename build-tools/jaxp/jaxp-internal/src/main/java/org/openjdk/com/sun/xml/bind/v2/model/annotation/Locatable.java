package org.openjdk.com.sun.xml.bind.v2.model.annotation;

import org.openjdk.com.sun.xml.bind.v2.runtime.Location;
/**
 * {@link Location} that is chained.
 *
 * <p>
 * {@link Locatable} forms a tree structure, where each {@link Locatable}
 * points back to the upstream {@link Locatable}.
 * For example, imagine {@link Locatable} X that points to a particular annotation,
 * whose upstream is {@link Locatable} Y, which points to a particular method
 * (on which the annotation is put), whose upstream is {@link Locatable} Z,
 * which points to a particular class (in which the method is defined),
 * whose upstream is {@link Locatable} W,
 * which points to another class (which refers to the class Z), and so on.
 *
 * <p>
 * This chain will be turned into a list when we report the error to users.
 * This allows them to know where the error happened
 * and why that place became relevant.
 *
 * @author Kohsuke Kawaguchi
 */
public interface Locatable {
    /**
     * Gets the upstream {@link Location} information.
     *
     * @return
     *      can be null.
     */
    Locatable getUpstream();

    /**
     * Gets the location object that this object points to.
     *
     * This operation could be inefficient and costly.
     */
    Location getLocation();
}