package com.tyron.builder.initialization;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.tyron.builder.api.internal.project.ProjectScript;
import com.tyron.builder.groovy.scripts.DefaultScript;
import com.tyron.builder.groovy.scripts.Script;
import com.tyron.builder.groovy.scripts.ScriptSource;
import com.tyron.builder.internal.classpath.Instrumented;
import com.tyron.builder.internal.scripts.ScriptOrigin;

import org.codehaus.groovy.runtime.callsite.CallSite;
import org.codehaus.groovy.runtime.callsite.CallSiteArray;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

import groovy.lang.GroovyObject;
import groovy.lang.MetaClass;

public class CodeAssistGradleApiSpecProvider implements GradleApiSpecProvider {
    @Override
    public Spec get() {
        return new GradleApiSpecAggregator.DefaultSpec(
                merge(getScriptExportedClasses(), getGroovyExportedClasses()),
                ImmutableSet.of(
                        "groovy.lang",
                        "org.codehaus.groovy.reflection",
                        "org.codehaus.groovy.runtime"
                ),
                ImmutableSet.of(),
                ImmutableSet.of()
        );
    }

    private Set<Class<?>> getGroovyExportedClasses() {
        return ImmutableSet.of(
                CallSite.class,
                CallSiteArray.class
        );
    }

    private Set<Class<?>> getScriptExportedClasses() {
        return ImmutableSet.of(
                DefaultScript.class,
                Script.class,
                ScriptSource.class,
                ScriptOrigin.class,
                Instrumented.class,
                ProjectScript.class,
                SettingsScript.class,
                InitScript.class
        );
    }

    @SafeVarargs
    private static <T> ImmutableSet<T> merge(Collection<T>... items) {
        ImmutableSet.Builder<T> builder = ImmutableSet.builder();
        for (Collection<T> item : items) {
            builder.addAll(item);
        }
        return builder.build();
    }
}
