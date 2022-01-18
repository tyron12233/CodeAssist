package com.tyron.completion.xml;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.tyron.builder.BuildModule;

import org.apache.bcel.Repository;
import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.Type;
import org.jetbrains.org.objectweb.asm.ClassReader;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Scans jar files and saves all the class files that extends {@link View} and has the
 * appropriate constructors to be inflated in XML.
 */
public class BytecodeScanner {

    private static boolean sBootScanned = false;

    public static List<JavaClass> scan(File file) throws IOException {
        scanBootstrapIfNeeded();

        String path = file.getAbsolutePath();
        List<JavaClass> viewClasses = new ArrayList<>();
        try (JarFile jarFile = new JarFile(file)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry element = entries.nextElement();
                if (!element.getName().endsWith(".class")) {
                    continue;
                }
                ClassParser classParser = new ClassParser(path, element.getName());
                JavaClass parse = classParser.parse();
                if (isViewClass(parse)) {
                    viewClasses.add(parse);
                }
            }
        }
        return viewClasses;
    }

    public static boolean isViewGroup(JavaClass javaClass) {
        JavaClass[] superClasses;
        try {
            superClasses = javaClass.getSuperClasses();
        } catch (ClassNotFoundException e) {
            return false;
        }
        for (JavaClass superClass : superClasses) {
            if (ViewGroup.class.getName().equals(superClass.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private static void scanBootstrapIfNeeded() {
        if (sBootScanned) {
            return;
        }
        File androidJar = BuildModule.getAndroidJar();
        if (androidJar != null && androidJar.exists()) {
            try (JarFile jarFile = new JarFile(androidJar)) {
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry element = entries.nextElement();
                    if (!element.getName().endsWith(".class")) {
                        continue;
                    }
                    ClassParser classParser = new ClassParser(androidJar.getAbsolutePath(),
                            element.getName());
                    JavaClass parse = classParser.parse();
                    Repository.addClass(parse);
                }
            } catch (IOException e) {
                // ignored
            }

            sBootScanned = true;
        }
    }

    private static boolean isViewClass(JavaClass javaClass) {
        try {
            JavaClass[] superClasses = javaClass.getSuperClasses();
            for (JavaClass superClass : superClasses) {
                if (View.class.getName().equals(superClass.getClassName())) {
                    Method[] methods = javaClass.getMethods();
                    if (containsViewConstructors(methods)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (ClassNotFoundException e) {
            return false;
        }
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
}
