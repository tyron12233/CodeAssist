package com.tyron.builder.compiler;

import com.tyron.builder.compiler.apk.PackageTask;
import com.tyron.builder.compiler.apk.SignTask;
import com.tyron.builder.compiler.apk.ZipAlignTask;
import com.tyron.builder.compiler.dex.R8Task;
import com.tyron.builder.compiler.firebase.GenerateFirebaseConfigTask;
import com.tyron.builder.compiler.incremental.dex.IncrementalD8Task;
import com.tyron.builder.compiler.incremental.java.IncrementalJavaTask;
import com.tyron.builder.compiler.incremental.kotlin.IncrementalKotlinCompiler;
import com.tyron.builder.compiler.incremental.resource.IncrementalAapt2Task;
import com.tyron.builder.compiler.java.CheckLibrariesTask;
import com.tyron.builder.compiler.log.InjectLoggerTask;
import com.tyron.builder.compiler.manifest.ManifestMergeTask;
import com.tyron.builder.compiler.symbol.MergeSymbolsTask;
import com.tyron.builder.compiler.viewbinding.GenerateViewBindingTask;
import com.tyron.builder.crashlytics.CrashlyticsTask;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.ModuleSettings;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;

import java.util.ArrayList;
import java.util.List;

public class AndroidAppBuilder extends BuilderImpl<AndroidModule> {

    public AndroidAppBuilder(Project project, AndroidModule module, ILogger logger) {
        super(project, module, logger);
    }

    @Override
    public List<Task<? super AndroidModule>> getTasks(BuildType type) {

        AndroidModule module = getModule();
        ILogger logger = getLogger();

        List<Task<? super AndroidModule>> tasks = new ArrayList<>();
        tasks.add(new CleanTask(getProject(), module, logger));
        tasks.add(new CheckLibrariesTask(getProject(), module, logger));
        tasks.add(new ManifestMergeTask(getProject(), module, logger));
        tasks.add(new GenerateFirebaseConfigTask(getProject(), module, logger));
        if (type == BuildType.DEBUG) {
            tasks.add(new InjectLoggerTask(getProject(), module, logger));
        }
        tasks.add(new CrashlyticsTask(getProject(), module, logger));
        tasks.add(new IncrementalAapt2Task(getProject(), module, logger, false));
        tasks.add(new GenerateViewBindingTask(getProject(), module, logger, true));
        tasks.add(new MergeSymbolsTask(getProject(), module, logger));
        tasks.add(new IncrementalKotlinCompiler(getProject(), module, logger));
        tasks.add(new IncrementalJavaTask(getProject(), module, logger));
        if (module.getSettings().getBoolean(ModuleSettings.USE_R8, false) &&
                type == BuildType.RELEASE) {
            tasks.add(new R8Task(getProject(), module, logger));
        } else {
            tasks.add(new IncrementalD8Task(getProject(), module, logger));
        }
        tasks.add(new PackageTask(getProject(), module, logger));
        if (module.getSettings().getBoolean(ModuleSettings.ZIP_ALIGN_ENABLED, false)) {
            tasks.add(new ZipAlignTask(getProject(), module, logger));
        }
        tasks.add(new SignTask(getProject(), module, logger));
        return tasks;
    }
}
