package org.gradle.plugins.ide.internal.configurer;

/**
 * Adapts any type of element to the generic {@link HierarchicalElementDeduplicator}.
 *
 * @param <T> the type of element to de-duplicate
 */
public interface HierarchicalElementAdapter<T> {

    /**
     * Returns the original name of the given element.
     *
     * @param element the element, cannot be null
     * @return the name of the element, never null
     */
    String getName(T element);

    /**
     * Returns the original identity name of the given element which
     * may be used to uniquely identify the element if the name returned
     * by getName() is not unique in the hierarchy.
     *
     * @param element the element, cannot be null
     * @return the identity name of the element, never null
     */
    String getIdentityName(T element);

    /**
     * Returns the parent in this element's hierarchy.
     *
     * @param element the child element, cannot be null
     * @return the parent element, may be null
     */
    T getParent(T element);
}
