package com.tyron.builder.api;

import groovy.util.Node;
import org.w3c.dom.Element;

/**
 * Provides various ways to access the content of an XML document.
 */
public interface XmlProvider {
    /**
     * Returns the XML document as a {@link StringBuilder}. Changes to the returned instance will be applied to the XML.
     * The returned instance is only valid until one of the other methods on this interface are called.
     *
     * @return A {@code StringBuilder} representation of the XML.
     */
    StringBuilder asString();

    /**
     * Returns the XML document as a Groovy {@link groovy.util.Node}. Changes to the returned instance will be applied
     * to the XML. The returned instance is only valid until one of the other methods on this interface are called.
     *
     * @return A {@code Node} representation of the XML.
     */
    Node asNode();

    /**
     * Returns the XML document as a DOM {@link org.w3c.dom.Element}. Changes to the returned instance will be applied
     * to the XML. The returned instance is only valid until one of the other methods on this interface are called.
     *
     * @return An {@code Element} representation of the XML.
     */
    Element asElement();
}
