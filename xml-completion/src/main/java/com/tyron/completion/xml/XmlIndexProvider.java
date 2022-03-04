package com.tyron.completion.xml;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.Module;
import com.tyron.completion.index.CompilerProvider;
import com.tyron.completion.index.CompilerService;

public class XmlIndexProvider extends CompilerProvider<XmlRepository> {

    @Nullable
    public static XmlRepository getRepository(@NonNull Project project, @NonNull AndroidModule module) {
        Object index = CompilerService.getInstance().getIndex(KEY);
        if (!(index instanceof XmlIndexProvider)) {
            return null;
        }

        XmlIndexProvider provider = ((XmlIndexProvider) index);
        return provider.get(project, module);
    }

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
        mRepository = null;
    }
}
