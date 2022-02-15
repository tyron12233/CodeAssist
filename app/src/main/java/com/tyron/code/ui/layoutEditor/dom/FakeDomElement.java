package com.tyron.code.ui.layoutEditor.dom;

import org.eclipse.lemminx.dom.DOMElement;

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

    public void setParent(DOMElement parent) {
        this.parent = parent;
    }
}
