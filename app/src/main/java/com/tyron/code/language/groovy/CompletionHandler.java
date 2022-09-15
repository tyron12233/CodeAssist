package com.tyron.code.language.groovy;

import com.tyron.code.language.java.Java;
import com.tyron.completion.java.provider.JavaSortCategory;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.DrawableKind;

import org.codehaus.groovy.ast.expr.MethodCallExpression;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CompletionHandler {

    private static final String BUILD_GRADLE = "build.gradle";
    private static final String SETTING_GRADLE = "settings.gradle";
    private static final String DEPENDENCYHANDLER_CLASS = "org.gradle.api.artifacts.dsl.DependencyHandler";
    private static final List<String> CONFIGURATIONS = List.of("implementation", "compileOnly", "runtimeOnly", "classpath", "api");

    public List<CompletionItem> getCompletionItems(MethodCallExpression containingCall, String fileName, String projectPath, Set<String> plugins) {
        Set<String> resultSet = new HashSet<>();
        List<CompletionItem> results = new ArrayList<>();
        List<String> delegateClassNames = new ArrayList<>();

        if (containingCall == null) {
            if (BUILD_GRADLE.equals(fileName)) {
                delegateClassNames.add(GradleDelegate.getDefault());
            } else if (SETTING_GRADLE.equals(fileName)) {
                delegateClassNames.add(GradleDelegate.getSettings());
            }
            results.addAll(getCompletionItemsFromExtClosures(projectPath, resultSet));
        } else {
            String methodName = containingCall.getMethodAsString();
            List<String> delegates = GradleDelegate.getDelegateMap().get(methodName);
            if (delegates == null) {
                return results;
            }
            delegateClassNames.addAll(delegates);
        }
        if (delegateClassNames.isEmpty()) {
            return Collections.emptyList();
        }
        for (String delegateClassName : delegateClassNames) {
            Class<?> delegateClass;
            try {
                delegateClass = Class.forName(delegateClassName);
            } catch (ClassNotFoundException e) {
                delegateClass = null;
            }
            if (delegateClass == null) {
                continue;
            }
            results.addAll(getCompletionItemsFromClass(delegateClass, plugins, resultSet));
            break;
        }


        return results;
    }

    private List<CompletionItem> getCompletionItemsFromClass(Class<?> javaClass, Set<String> plugins, Set<String> resultSet) {
        List<CompletionItem> results = new ArrayList<>();
        for (Class<?> superInterface : javaClass.getInterfaces()) {
            results.addAll(getCompletionItemsFromClass(superInterface, plugins, resultSet));
        }
        Class<?> superclass = javaClass.getSuperclass();
        if (superclass != null) {
            results.addAll(getCompletionItemsFromClass(superclass, plugins, resultSet));
        }

        Method[] methods = javaClass.getMethods();
        for (Method method : methods) {
            boolean isMethodDeprecated = isDeprecated(method);
            String methodName = method.getName();

            // When parsing a abstract class, we'll get a "<init>" method which can't be
            // called directly,
            // So we filter it here.
            if (methodName.equals("<init>")) {
                continue;
            }
            List<String> arguments = new ArrayList<>();
            Arrays.asList(method.getParameterTypes()).forEach(type ->
                    arguments.add(type.getName()));
            CompletionItem item = generateCompletionItemForMethod(methodName, arguments, isMethodDeprecated);
            if (resultSet.add(item.getLabel())) {
                results.add(item);
            }
            int modifiers = method.getModifiers();
            // See:
            // https://docs.gradle.org/current/userguide/custom_gradle_types.html#managed_properties
            // we offer managed properties for an abstract getter method;
            if (methodName.startsWith("get") && methodName.length() > 3 && Modifier.isPublic(modifiers)
                && Modifier.isAbstract(modifiers)) {
                String propertyName = methodName.substring(3, 4).toLowerCase() + methodName.substring(4);
                CompletionItem property = new CompletionItem(propertyName);
                property.commitText = propertyName;
                property.detail = "Property";
                property.iconKind = DrawableKind.Attribute;
                property.setSortText(JavaSortCategory.ACCESSIBLE_SYMBOL.toString());
                property.addFilterText(propertyName);
                if (resultSet.add(propertyName)) {
                    results.add(property);
                }
            }
        }
        boolean shouldAddConfigurations = !Collections.disjoint(
                plugins,
                List.of("com.android.application", "application", "java", "java-library")
        );
        if (shouldAddConfigurations && javaClass.getName().equals(DEPENDENCYHANDLER_CLASS)) {
            for (String configuration : CONFIGURATIONS) {
                String builder = configuration + "(Object... o)";
                String insertBuilder = configuration + "()";
                CompletionItem item = new CompletionItem(builder);
                item.iconKind = DrawableKind.Method;
                item.commitText = insertBuilder;
                item.addFilterText(configuration);
                item.setSortText(JavaSortCategory.ACCESSIBLE_SYMBOL.toString());
                results.add(item);
            }
        }
        return results;
    }

    private List<CompletionItem> getCompletionItemsFromExtClosures(String projectPath,
                                                                   Set<String> resultSet) {
        return Collections.emptyList();
    }

    private static CompletionItem generateCompletionItemForMethod(String name, List<String> arguments,
                                                                  boolean deprecated) {
        StringBuilder labelBuilder = new StringBuilder();
        labelBuilder.append(name);
        labelBuilder.append("(");
        for (int i = 0; i < arguments.size(); i++) {
            String type = arguments.get(i);
            String[] classNameSplits = type.split("\\.");
            String className = classNameSplits[classNameSplits.length - 1];
            String variableName = className.substring(0, 1).toLowerCase();
            labelBuilder.append(className);
            labelBuilder.append(" ");
            labelBuilder.append(variableName);
            if (i != arguments.size() - 1) {
                labelBuilder.append(", ");
            }
        }
        labelBuilder.append(")");
        String label = labelBuilder.toString();
        CompletionItem item = new CompletionItem(label);
        item.addFilterText(name);
        item.setSortText(JavaSortCategory.ACCESSIBLE_SYMBOL.toString());
        item.iconKind = DrawableKind.Method;

        StringBuilder builder = new StringBuilder();
        builder.append(name);
        if (label.endsWith("(Closure c)")) {
            // for single closure, we offer curly brackets
            builder.append(" {}");
        } else {
            builder.append("()");
        }
        item.commitText = builder.toString();
        return item;
    }

    private static boolean isDeprecated(Method object) {
        try {
            Deprecated annotation = object.getAnnotation(Deprecated.class);
            return annotation != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
