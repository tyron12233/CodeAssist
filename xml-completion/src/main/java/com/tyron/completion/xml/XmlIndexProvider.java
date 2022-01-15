package com.tyron.completion.xml;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.Module;
import com.tyron.completion.index.CompilerProvider;

public class XmlIndexProvider extends CompilerProvider<XmlRepository> {

    public static final String KEY = XmlIndexProvider.class.getSimpleName();

    @Override
    public XmlRepository get(Project project, Module module) {
        return new XmlRepository();
    }
}
