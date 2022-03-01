package com.tyron.builder.crashlytics;

import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.xml.completion.repository.Repository;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;

/**
 * Task to inject crashlytics build id to the resource directory
 */
public class CrashlyticsTask extends Task<AndroidModule> {

    private static final String TAG = CrashlyticsTask.class.getSimpleName();

    private static final String CORE_CLASS = "com.google.firebase.crashlytics.internal.common.CrashlyticsCore";

    private boolean mContainsCrashlytics;

    public CrashlyticsTask(Project project, AndroidModule module, ILogger logger) {
        super(project, module, logger);
    }

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public void prepare(BuildType type) throws IOException {
        File javaFile = getModule().getJavaFile(CORE_CLASS);
        mContainsCrashlytics = javaFile.exists();
    }

    @Override
    public void run() throws IOException, CompilationFailedException {
        if (!mContainsCrashlytics) {
            return;
        }
    }

    public static Repository getRepository() {
        try {
            Class<?> clazz = Class.forName("com.tyron.completion.xml.XmlRepository");
            Method method =
                    clazz.getDeclaredMethod("getRepository", Project.class, AndroidModule.class);
            method.invoke(null, m)
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
