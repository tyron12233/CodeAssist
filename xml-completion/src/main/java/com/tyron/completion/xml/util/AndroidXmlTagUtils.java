package com.tyron.completion.xml.util;

import android.view.View;

import androidx.annotation.Nullable;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.java.JavaCompilerProvider;
import com.tyron.completion.java.compiler.CompilerContainer;
import com.tyron.completion.java.compiler.JavaCompilerService;

import org.openjdk.javax.lang.model.element.TypeElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class AndroidXmlTagUtils {

    private static final ImmutableSet<String> sPackagesToSkip;

    static {
        ImmutableSet.Builder<String> packages = ImmutableSet.builder();
        packages.add("java");
        packages.add("android.text");
        packages.add("android.content");
        packages.add("android.system");
        packages.add("android.transition");
        packages.add("android.accounts");
        packages.add("android.location");
        packages.add("android.os");
        packages.add("android.util");
        sPackagesToSkip = packages.build();
    }

    @Nullable
    private static JavaCompilerService getCompiler(Project project, AndroidModule module) {
        JavaCompilerProvider provider = CompilerService.getInstance().getIndex(JavaCompilerProvider.KEY);
        if (provider == null) {
            return null;
        }
        return provider.getCompiler(project, module);
    }

    public static List<String> getTagCompletions(Project project,
                                                 AndroidModule module) {
        List<String> tags = new ArrayList<>();
        JavaCompilerService compiler = getCompiler(project, module);
        if (compiler == null) {
            return tags;
        }

        CompilerContainer container = compiler.getCachedContainer();
        return container.getWithLock(task -> {
            if (task == null) {
                return tags;
            }

            TypeElement viewElement = task.task.getElements()
                    .getTypeElement(View.class.getName());
            if (viewElement == null) {
                return tags;
            }

            Set<String> types = compiler.publicTopLevelTypes();
            for (String type : types) {
                String packageName = type.substring(0, type.lastIndexOf('.'));
                if (sPackagesToSkip.contains(packageName)) {
                    continue;
                }
                TypeElement element = task.task.getElements()
                        .getTypeElement(type);
                if (element == null) {
                    continue;
                }
                boolean assignable = task.task.getTypes()
                        .isAssignable(element.asType(), viewElement.asType());
                if (assignable) {
                    tags.add(type);
                }
            }
            return tags;
        });
    }
}
