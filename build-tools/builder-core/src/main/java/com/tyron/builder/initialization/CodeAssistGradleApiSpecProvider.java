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

public class CodeAssistGradleApiSpecProvider extends GradleApiSpecProvider.SpecAdapter implements GradleApiSpecProvider {
    @Override
    public Set<Class<?>> getExportedClasses() {
        return ImmutableSet.of();
    }

    @Override
    public Set<String> getExportedPackages() {
        return ImmutableSet.of(
                "com.tyron.builder",
                "org.apache.tools.ant",
                "groovy",
                "org.codehaus.groovy",
                "groovyjarjarantlr",
                "org.slf4j",
                "org.apache.commons.logging",
                "org.apache.log4j",
                "javax.annotation",
                "javax.inject");
    }

    @Override
    public Set<String> getExportedResourcePrefixes() {
        return ImmutableSet.of(
                "META-INF/gradle-plugins"
        );
    }

    @Override
    public Set<String> getExportedResources() {
        return ImmutableSet.of(
                "META-INF/groovy/org.codehaus.groovy.runtime.ExtensionModule",
                "META-INF/services/org.apache.groovy.json.FastStringServiceFactory"
        );
    }

    @Override
    public Spec get() {
        return this;
    }
}
