package org.openjdk.com.sun.xml.bind.v2.runtime;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.openjdk.javax.xml.bind.JAXBContext;
import org.openjdk.javax.xml.bind.JAXBException;

import org.openjdk.com.sun.xml.bind.v2.model.annotation.Locatable;

/**
 * Signals an incorrect use of JAXB annotations.
 *
 * @author Kohsuke Kawaguchi (kk@kohsuke.org)
 * @since JAXB 2.0 EA1
 */
public class IllegalAnnotationException extends JAXBException {

    /**
     * Read-only list of {@link Location}s.
     */
    private final List<List<Location>> pos;

    private static final long serialVersionUID = 1L;

    public IllegalAnnotationException(String message, Locatable src) {
        super(message);
        pos = build(src);
    }

    public IllegalAnnotationException(String message, Annotation src) {
        this(message,cast(src));
    }

    public IllegalAnnotationException(String message, Locatable src1, Locatable src2) {
        super(message);
        pos = build(src1,src2);
    }

    public IllegalAnnotationException(String message, Annotation src1, Annotation src2) {
        this(message,cast(src1),cast(src2));
    }

    public IllegalAnnotationException(String message, Annotation src1, Locatable src2) {
        this(message,cast(src1),src2);
    }

    public IllegalAnnotationException(String message, Throwable cause, Locatable src) {
        super(message, cause);
        pos = build(src);
    }

    private static Locatable cast(Annotation a) {
        if (a instanceof Locatable) {
            return (Locatable) a;
        } else {
            return null;
        }
    }

    private List<List<Location>> build(Locatable... srcs) {
        List<List<Location>> r = new ArrayList<List<Location>>();
        for( Locatable l : srcs ) {
            if(l!=null) {
                List<Location> ll = convert(l);
                if (ll != null && !ll.isEmpty()) {
                    r.add(ll);
                }
            }
        }
        return Collections.unmodifiableList(r);
    }

    /**
     * Builds a list of {@link Location}s out of a {@link Locatable}.
     */
    private List<Location> convert(Locatable src) {
        if (src == null) {
            return null;
        }

        List<Location> r = new ArrayList<Location>();
        for( ; src!=null; src=src.getUpstream())
            r.add(src.getLocation());
        return Collections.unmodifiableList(r);
    }



    /**
     * Returns a read-only list of {@link Location} that indicates
     * where in the source code the problem has happened.
     *
     * <p>
     * Normally, an annotation error happens on one particular
     * annotation, in which case this method returns a list that
     * contains another list, which in turn contains the location
     * information that leads to the error location
     * (IOW, {@code [ [pos1,pos2,...,posN] ]})
     *
     * <p>
     * Sometimes, an error could occur because of two or more conflicting
     * annotations, in which case this method returns a list
     * that contains many lists, where each list contains
     * the location information that leads to each of the conflicting
     * annotations
     * (IOW, {@code [ [pos11,pos12,...,pos1N],[pos21,pos22,...,pos2M], ... ]})
     *
     * <p>
     * Yet some other time, the runtime can fail to provide any
     * error location, in which case this method returns an empty list.
     * (IOW, {@code []}). We do try hard to make sure this won't happen,
     * so please <a href="http://jaxb.dev.java.net/">let us know</a>
     * if you see this behavior.
     *
     *
     * <h3>List of {@link Location}</h3>
     * <p>
     * Each error location is identified not just by one {@link Location}
     * object, but by a sequence of {@link Location}s that shows why
     * the runtime is led to the place of the error.
     * This list is sorted such that the most specific {@link Location} comes
     * to the first in the list, sort of like a stack trace.
     *
     * <p>
     * For example, suppose you specify class {@code Foo} to {@link JAXBContext},
     * {@code Foo} derives from {@code Bar}, {@code Bar} has a field {@code pea}
     * that points to {@code Zot}, {@code Zot} contains a {@code gum}
     * property, and this property has an errornous annotation.
     * Then when this exception is thrown, the list of {@link Location}s
     * will look something like
     * {@code [ "gum property", "Zot class", "pea property", "Bar class", "Foo class" ]}
     *
     *
     * @return
     *      can be empty when no source position is available,
     *      but never null. The returned list will never contain
     *      null nor length-0 {@link List}.
     */
    public List<List<Location>> getSourcePos() {
        return pos;
    }

    /**
     * Returns the exception name, message, and related information
     * together in one string.
     *
     * <p>
     * Overriding this method (instead of {@link #printStackTrace} allows
     * this crucial detail to show up even when this exception is nested
     * inside other exceptions.
     */
    public String toString() {
        StringBuilder sb = new StringBuilder(getMessage());

        for( List<Location> locs : pos ) {
            sb.append("\n\tthis problem is related to the following location:");
            for( Location loc : locs )
                sb.append("\n\t\tat ").append(loc.toString());
        }

        return sb.toString();
    }
}