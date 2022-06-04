package com.tyron.builder.api.internal;

import com.tyron.builder.api.internal.classpath.ModuleRegistry;
import com.tyron.builder.internal.classpath.ClassPath;

public class DefaultClassPathProvider implements ClassPathProvider {
    private final ModuleRegistry moduleRegistry;

    public DefaultClassPathProvider(ModuleRegistry moduleRegistry) {
        this.moduleRegistry = moduleRegistry;
    }

    @Override
    public ClassPath findClassPath(String name) {
        if (name.equals("GRADLE_RUNTIME")) {
            return moduleRegistry.getModule("gradle-launcher").getAllRequiredModulesClasspath();
        }
        if (name.equals("GRADLE_INSTALLATION_BEACON")) {
            return moduleRegistry.getModule("gradle-installation-beacon").getImplementationClasspath();
        }
        if (name.equals("GROOVY-COMPILER")) {
            ClassPath classpath = ClassPath.EMPTY;
            classpath = classpath.plus(moduleRegistry.getModule("gradle-language-groovy").getImplementationClasspath());
            classpath = classpath.plus(moduleRegistry.getExternalModule("groovy").getClasspath());
            classpath = classpath.plus(moduleRegistry.getExternalModule("groovy-json").getClasspath());
            classpath = classpath.plus(moduleRegistry.getExternalModule("groovy-xml").getClasspath());
            classpath = classpath.plus(moduleRegistry.getExternalModule("asm").getClasspath());
            classpath = addJavaCompilerModules(classpath);
            return classpath;
        }
        if (name.equals("SCALA-COMPILER")) {
            ClassPath classpath = ClassPath.EMPTY;
            classpath = classpath.plus(moduleRegistry.getModule("gradle-scala").getImplementationClasspath());
            classpath = addJavaCompilerModules(classpath);
            return classpath;
        }
        if (name.equals("JAVA-COMPILER")) {
            return addJavaCompilerModules(ClassPath.EMPTY);
        }
        if (name.equals("DEPENDENCIES-EXTENSION-COMPILER")) {
            ClassPath classpath = ClassPath.EMPTY;
            classpath = classpath.plus(moduleRegistry.getModule("gradle-base-annotations").getImplementationClasspath());
            classpath = classpath.plus(moduleRegistry.getModule("gradle-base-services").getImplementationClasspath());
            classpath = classpath.plus(moduleRegistry.getModule("gradle-core-api").getImplementationClasspath());
            classpath = classpath.plus(moduleRegistry.getModule("gradle-core").getImplementationClasspath());
            classpath = classpath.plus(moduleRegistry.getModule("gradle-dependency-management").getImplementationClasspath());
            classpath = classpath.plus(moduleRegistry.getExternalModule("javax.inject").getClasspath());
            return classpath;
        }
        if (name.equals("JAVA-COMPILER-PLUGIN")) {
            return ClassPath.EMPTY;
//            return addJavaCompilerModules(moduleRegistry.getModule("gradle-java-compiler-plugin").getImplementationClasspath());
        }
        if (name.equals("ANT")) {
            ClassPath classpath = ClassPath.EMPTY;
            classpath = classpath.plus(moduleRegistry.getExternalModule("ant").getClasspath());
            classpath = classpath.plus(moduleRegistry.getExternalModule("ant-launcher").getClasspath());
            return classpath;
        }

        return null;
    }

    private ClassPath addJavaCompilerModules(ClassPath classpath) {
        classpath = classpath.plus(moduleRegistry.getModule("gradle-language-java").getImplementationClasspath());
        classpath = classpath.plus(moduleRegistry.getModule("gradle-language-jvm").getImplementationClasspath());
        classpath = classpath.plus(moduleRegistry.getModule("gradle-platform-base").getImplementationClasspath());
        return classpath;
    }
}
