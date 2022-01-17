package com.tyron.completion.xml;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.Module;
import com.tyron.completion.index.CompilerProvider;

import org.apache.bcel.Repository;

public class XmlIndexProvider extends CompilerProvider<XmlRepository> {

    public static final String KEY = XmlIndexProvider.class.getSimpleName();

    private XmlRepository mRepository;

    @Override
    public XmlRepository get(Project project, Module module) {
        if (mRepository == null) {
            mRepository = new XmlRepository();
        }
        return mRepository;
    }

    public void clear() {
        Repository.clearCache();
        mRepository = null;
    }
}
