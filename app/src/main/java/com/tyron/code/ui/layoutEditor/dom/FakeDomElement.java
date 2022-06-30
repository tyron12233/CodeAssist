package com.tyron.code.ui.layoutEditor.dom;

import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMNode;

public class FakeDomElement extends DOMElement {

    private DOMElement parent;

    private String tagName;

    public FakeDomElement(int start, int end) {
        super(start, end);
    }

    @Override
    public String getTagName() {
        return tagName;
    }

    public void setTagName(String tagName) {
        this.tagName = tagName;
    }

    public DOMElement getParent() {
        return parent;
    }

    @Override
    public DOMElement getParentElement() {
        return getParent();
    }

    @Override
    public DOMNode getParentNode() {
        return getParent();
    }

    public void setParent(DOMElement parent) {
        this.parent = parent;
    }
}
