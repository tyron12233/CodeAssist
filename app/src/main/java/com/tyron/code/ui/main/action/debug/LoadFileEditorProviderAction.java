package com.tyron.code.ui.main.action.debug;

import androidx.annotation.NonNull;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;
import com.tyron.actions.ActionManager;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.builder.BuildModule;
import com.tyron.code.ui.editor.impl.FileEditorProviderManagerImpl;
import com.tyron.fileeditor.api.FileEditorProvider;

import org.openjdk.com.sun.org.apache.bcel.internal.classfile.ClassParser;
import org.openjdk.com.sun.org.apache.bcel.internal.classfile.JavaClass;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import dalvik.system.PathClassLoader;

public class LoadFileEditorProviderAction extends BaseLoadAction {

    @Override
    public void update(@NonNull AnActionEvent event) {
        super.update(event);

        event.getPresentation().setVisible(true);
        event.getPresentation().setText("Load FileEditorProvider");
    }

    @Override
    public List<JavaClass> getApplicableClasses(String file) throws IOException {
        List<JavaClass> providerClasses = new ArrayList<>();

        JarFile jarFile = new JarFile(file);
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (!entry.getName().endsWith(".class")) {
                continue;
            }

            ClassParser parser = new ClassParser(file, entry.getName());
            JavaClass javaClass = parser.parse();

            if (isFileEditorProvider(javaClass)) {
                providerClasses.add(javaClass);
            }
        }
        return providerClasses;
    }

    @Override
    public void loadClasses(String file, List<JavaClass> classes) throws ClassNotFoundException, IllegalAccessException, InstantiationException, CompilationFailedException, IOException {
        dexAndLoadJar(file, classes);
    }

    private void dexAndLoadJar(String file, List<JavaClass> javaClasses) throws IOException, CompilationFailedException, ClassNotFoundException, InstantiationException, IllegalAccessException {
        File jarFile = new File(file);

        Path temp_output = Files.createTempDirectory("temp_output");

        D8Command command = D8Command.builder()
                .addProgramFiles(jarFile.toPath())
                .addLibraryFiles(BuildModule.getAndroidJar().toPath())
                .addLibraryFiles(BuildModule.getLambdaStubs().toPath())
                .setOutput(temp_output, OutputMode.DexIndexed)
                .build();
        D8.run(command);

        StringBuilder path = new StringBuilder();
        File[] files = temp_output.toFile().listFiles();
        for (File it : files) {
            path.append(it.getAbsolutePath());
            path.append(File.pathSeparator);
        }

        PathClassLoader classLoader = new PathClassLoader(path.substring(0, path.length() - 1),
                getClass().getClassLoader());
        for (JavaClass javaClass : javaClasses) {
            Class<?> aClass = classLoader.loadClass(javaClass.getClassName());
            Class<? extends FileEditorProvider> actionClass = aClass.asSubclass(FileEditorProvider.class);
            FileEditorProvider provider = actionClass.newInstance();
            FileEditorProviderManagerImpl.getInstance().registerProvider(provider);
        }
    }

    private boolean isFileEditorProvider(JavaClass javaClass) {
        JavaClass current = javaClass;
        while (current != null) {
            String[] interfaces = current.getInterfaceNames();
            if (interfaces == null) {
                return false;
            }
            for (String anInterface : interfaces) {
                if (anInterface.equals(FileEditorProvider.class.getName())) {
                    return true;
                }
            }
            current = javaClass.getSuperClass();
        }
        return false;
    }
}
