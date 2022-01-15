package com.tyron.completion.xml.model;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class DeclareStyleable implements Comparable<DeclareStyleable>{

    private String name;

    private Set<AttributeInfo> attributeInfos;

    public DeclareStyleable(String name, Set<AttributeInfo> attributeInfos) {
        this.name = name;
        this.attributeInfos = attributeInfos;
    }

    public Set<AttributeInfo> getAttributeInfos() {
        return attributeInfos;
    }

    public String getName() {
        return name;
    }

    @Override
    public int compareTo(DeclareStyleable o) {
        return Objects.compare(this.name, o.name, String::compareTo);
    }
}
