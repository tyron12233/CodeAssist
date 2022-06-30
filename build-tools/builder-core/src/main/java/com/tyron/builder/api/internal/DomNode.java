package com.tyron.builder.api.internal;

import groovy.lang.GroovySystem;
import groovy.util.Node;

/**
 * A node which represents the root of an XML document.
 */
public class DomNode extends Node {
    private String publicId;
    private String systemId;

    static {
        setMetaClass(GroovySystem.getMetaClassRegistry().getMetaClass(DomNode.class), DomNode.class);
    }
    
    public String getPublicId() {
        return publicId;
    }

    public void setPublicId(String publicId) {
        this.publicId = publicId;
    }

    public String getSystemId() {
        return systemId;
    }

    public void setSystemId(String systemId) {
        this.systemId = systemId;
    }

    public DomNode(Object name) {
        super(null, name);
    }
}
