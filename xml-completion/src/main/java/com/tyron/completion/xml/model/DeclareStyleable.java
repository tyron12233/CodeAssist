package com.tyron.completion.xml.model;

import android.text.TextUtils;

import com.tyron.completion.xml.XmlRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public class DeclareStyleable implements Comparable<DeclareStyleable>{

    private final String parent;
    private String name;

    private Set<AttributeInfo> attributeInfos;

    public DeclareStyleable(String name, Set<AttributeInfo> attributeInfos) {
        this(name, attributeInfos, "");
    }

    public DeclareStyleable(String name, Set<AttributeInfo> attributeInfos, String parent) {
        this.name = name;
        this.attributeInfos = attributeInfos;
        this.parent = parent;
    }

    public Set<AttributeInfo> getAttributeInfos() {
        return attributeInfos;
    }

    public Set<AttributeInfo> getAttributeInfosWithParents(XmlRepository repository) {
        Set<AttributeInfo> attributeInfos = new TreeSet<>(getAttributeInfos());
        String[] parents = parent.split(" ");
        for (String parent : parents) {
            DeclareStyleable declareStyleable = repository.getDeclareStyleables().get(parent);
            if (declareStyleable == null) {
                declareStyleable = repository.getManifestAttrs().get(parent);
            }
            if (declareStyleable != null) {
                attributeInfos.addAll(declareStyleable.getAttributeInfosWithParents(repository));
            }
        }
        return attributeInfos;
    }

    public String getName() {
        return name;
    }

    @Override
    public int compareTo(DeclareStyleable o) {
        return Objects.compare(this.name, o.name, String::compareTo);
    }

    public String getParent() {
        return parent;
    }
}
