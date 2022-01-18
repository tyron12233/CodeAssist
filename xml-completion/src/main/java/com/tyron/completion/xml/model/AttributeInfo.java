package com.tyron.completion.xml.model;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public class AttributeInfo implements Comparable<AttributeInfo> {

    private final String parent;
    private String name;

    private final Set<Format> formats;

    private final List<String> values;
    private String namespace;

    public AttributeInfo(String name, Set<Format> formats, List<String> values) {
        this(name, formats, values, "");
    }

    public AttributeInfo(String name, Set<Format> formats, List<String> values, String parent) {
        this.name = name;
        this.formats = formats;
        this.values = values;
        this.parent = parent;
    }

    @Override
    public int compareTo(AttributeInfo o) {
        return Objects.compare(this.name, o.name, String::compareTo);
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public List<String> getValues() {
        return values;
    }

    public Set<Format> getFormats() {
        return formats;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getParent() {
        return parent;
    }
}
