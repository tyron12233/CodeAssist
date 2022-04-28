package com.tyron.code.ui.main.action.debug;

import android.app.Activity;

import androidx.annotation.NonNull;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;
import com.github.angads25.filepicker.model.DialogConfigs;
import com.github.angads25.filepicker.model.DialogProperties;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tyron.actions.ActionManager;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.Presentation;
import com.tyron.builder.BuildModule;
import com.tyron.builder.project.Project;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.ui.file.FilePickerDialogFixed;

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

public class LoadActionJarAction extends BaseLoadAction {

    @Override
    public void update(@NonNull AnActionEvent event) {
        Presentation presentation = event.getPresentation();
        presentation.setVisible(true);
        presentation.setText("Load action plugin");
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
            Class<? extends AnAction> actionClass = aClass.asSubclass(AnAction.class);
            AnAction anAction = actionClass.newInstance();
            ActionManager.getInstance().replaceAction(file, anAction);
        }
    }

    @Override
    public List<JavaClass> getApplicableClasses(String file) throws IOException {
        List<JavaClass> actionClasses = new ArrayList<>();

        JarFile jarFile = new JarFile(file);
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (!entry.getName().endsWith(".class")) {
                continue;
            }

            ClassParser parser = new ClassParser(file, entry.getName());
            JavaClass javaClass = parser.parse();

            if (isActionClass(javaClass)) {
                actionClasses.add(javaClass);
            }
        }
        return actionClasses;
    }

    @Override
    public void loadClasses(String file, List<JavaClass> classes) throws ClassNotFoundException,
            IllegalAccessException, InstantiationException, CompilationFailedException, IOException {
        dexAndLoadJar(file, classes);
    }

    private boolean isActionClass(JavaClass javaClass) {
        JavaClass current = javaClass;
        while (current != null) {
            String superClass = current.getSuperclassName();
            if (superClass == null) {
                return false;
            }
            if (superClass.equals(AnAction.class.getName())) {
                return true;
            }
            current = javaClass.getSuperClass();
        }
        return false;
    }
}
