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

public class LoadActionJarAction extends AnAction {

    @Override
    public void update(@NonNull AnActionEvent event) {
        Presentation presentation = event.getPresentation();
        presentation.setVisible(true);
        presentation.setText("Load action plugin");
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        Activity activity = e.getRequiredData(CommonDataKeys.ACTIVITY);
        Project project = e.getRequiredData(CommonDataKeys.PROJECT);

        DialogProperties properties = new DialogProperties();
        properties.selection_mode = DialogConfigs.SINGLE_MODE;
        properties.selection_type = DialogConfigs.FILE_SELECT;
        properties.extensions = new String[]{"jar"};
        properties.root = project.getRootFile();

        FilePickerDialogFixed dialog = new FilePickerDialogFixed(activity, properties);
        dialog.show();
        dialog.setDialogSelectionListener(files -> {
            try {
                List<JavaClass> javaClasses = loadJar(files[0]);
                String[] names = javaClasses.stream()
                        .map(JavaClass::getClassName)
                        .toArray(String[]::new);
                boolean[] checked = new boolean[names.length];
                new MaterialAlertDialogBuilder(activity)
                        .setTitle("Detected classes")
                        .setMultiChoiceItems(names, checked, (d, which, isChecked) -> {

                        })
                        .setPositiveButton("Load", (d, which) -> {
                            try {
                                dexAndLoadJar(files[0], javaClasses);
                            } catch (Exception exception) {
                                ApplicationLoader.showToast(exception.getMessage());
                            }
                        })
                        .show();
            } catch (IOException exception) {
                ApplicationLoader.showToast(exception.getMessage());
            }
        });
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

    private List<JavaClass> loadJar(String file) throws IOException {
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
