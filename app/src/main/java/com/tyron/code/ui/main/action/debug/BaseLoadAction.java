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

public abstract class BaseLoadAction extends AnAction {

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
                List<JavaClass> javaClasses = getApplicableClasses(files[0]);
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
                                loadClasses(files[0], javaClasses);
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

    public abstract List<JavaClass> getApplicableClasses(String jarFile) throws IOException;

    public abstract void loadClasses(String file, List<JavaClass> classes) throws ClassNotFoundException, IllegalAccessException, InstantiationException, CompilationFailedException, IOException;
}
