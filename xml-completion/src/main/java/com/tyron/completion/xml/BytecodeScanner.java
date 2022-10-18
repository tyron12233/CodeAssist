package com.tyron.completion.xml;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.BuildModule;
import com.tyron.completion.xml.util.PartialClassParser;

import org.apache.bcel.Repository;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.Type;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Scans jar files and saves all the class files that extends {@link View} and has the
 * appropriate constructors to be inflated in XML.
 */
public class BytecodeScanner {

    private static final Predicate<String> CLASS_NAME_FILTER = s -> s.endsWith(".class");

    private static final Set<String> sIgnoredPaths;

    static {
        ImmutableSet.Builder<String> builder = ImmutableSet.builder();
        builder.add("android");
        builder.add("android/util");
        builder.add("android/os");
        builder.add("android/os/health");
        builder.add("android/os/strictmode");
        builder.add("android/os/storage");
        builder.add("android/graphics");
        builder.add("android/graphics/drawable");
        builder.add("android/graphics/fonts");
        builder.add("android/graphics/pdf");
        builder.add("android/graphics/text");
        builder.add("android/system");
        builder.add("android/content");
        builder.add("android/content/res");
        builder.add("android/content/pm");
        sIgnoredPaths = builder.build();
    }

    public static void loadJar(File jar) throws IOException {
        try (JarFile jarFile = new JarFile(jar)) {
            iterateClasses(jarFile, element -> {
                PartialClassParser classParser =
                        new PartialClassParser(jar.getAbsolutePath(), element.getName());
                try {
                    Repository.addClass(classParser.parse());
                } catch (IOException e) {
                    // ignored, keep parsing other classes
                }
            });
        }
    }

    public static List<JavaClass> scan(File file) throws IOException {
        List<JavaClass> viewClasses = new ArrayList<>();
        try (JarFile jarFile = new JarFile(file)) {
            iterateClasses(jarFile, element -> {
                String fqn = element.getName().replace('/', '.')
                        .substring(0, element.getName().length() - ".class".length());
                try {
                    JavaClass javaClass = Repository.lookupClass(fqn);
                    if (isViewClass(javaClass)) {
                        viewClasses.add(javaClass);
                    }
                } catch (ClassNotFoundException e) {
                    // should not happen, the class should already be loaded here.
                }
            });
        }
        return viewClasses;
    }

    public static boolean isViewGroup(JavaClass javaClass) {
        JavaClass[] superClasses = getSuperClasses(javaClass);
        return Arrays.stream(superClasses)
                .anyMatch(it -> ViewGroup.class.getName().equals(it.getClassName()));
    }

    public static void scanBootstrapIfNeeded() {
        if (!needScanBootstrap()) {
            return;
        }

        File androidJar = BuildModule.getAndroidJar();
        if (androidJar != null && androidJar.exists()) {
            try (JarFile jarFile = new JarFile(androidJar)) {
                iterateClasses(jarFile, element -> {
                    String name = element.getName();
                    String packagePath = name.substring(0, name.lastIndexOf('/'));
                    if (sIgnoredPaths.contains(packagePath)) {
                        return;
                    }
                    if (packagePath.startsWith("java/")) {
                        return;
                    }
                    PartialClassParser classParser =
                            new PartialClassParser(androidJar.getAbsolutePath(), name);
                    try {
                        Repository.addClass(classParser.parse());
                    } catch (IOException e) {
                        // ignored, keep parsing other classes
                    }

                });
            } catch (IOException e) {
                // ignored
            }
        }
    }

    private static boolean needScanBootstrap() {
        try {
            Repository.getRepository().loadClass(View.class);
            return false;
        } catch (ClassNotFoundException e) {
            return true;
        }
    }

    private static boolean isViewClass(JavaClass javaClass) {
        JavaClass[] superClasses = getSuperClasses(javaClass);
        return Arrays.stream(superClasses)
                .anyMatch(it -> View.class.getName().equals(it.getClassName()));
    }

    /**
     * Get the array of java classes even if the root class does not exist
     *
     * @param javaClass The java class
     * @return array of super classes
     */
    public static JavaClass[] getSuperClasses(JavaClass javaClass) {
        List<JavaClass> superClasses = new ArrayList<>();
        JavaClass current = javaClass;
        while (current != null && current.getSuperclassName() != null) {
            JavaClass lookupClass;
            try {
                lookupClass = Repository.lookupClass(current.getSuperclassName());
            } catch (ClassNotFoundException e) {
                lookupClass = null;
            }

            if (lookupClass != null) {
                superClasses.add(lookupClass);
            }
            current = lookupClass;
        }
        return superClasses.toArray(new JavaClass[0]);
    }

    private static boolean containsViewConstructors(Method[] methods) {
        for (Method method : methods) {
            if (!"<init>".equals(method.getName())) {
                continue;
            }

            Type[] argumentTypes = method.getArgumentTypes();
            if (argumentTypes.length == 2) {
                if (Context.class.getName().equals(argumentTypes[0].toString())) {
                    if (AttributeSet.class.getName().equals(argumentTypes[1].toString())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static void iterateClasses(JarFile jarFile, Consumer<JarEntry> consumer) {
        iterate(jarFile, CLASS_NAME_FILTER, consumer);
    }

    public static void iterate(JarFile jarFile,
                               Predicate<String> nameFilter,
                               Consumer<JarEntry> consumer) {
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (nameFilter.test(entry.getName())) {
                consumer.accept(entry);
            }
        }
    }
}
