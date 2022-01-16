package com.tyron.completion.xml.model;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public class AttributeInfo implements Comparable<AttributeInfo> {

    private String name;

    private Set<Format> formats;

    private List<String> values;
    private String namespace;

    public AttributeInfo(String name, Set<Format> formats, List<String> values) {
        this.name = name;
        this.formats = formats;
        this.values = values;
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
}
