package com.tyron.builder.compiler;

import com.tyron.builder.compiler.aab.AabTask;
import com.tyron.builder.compiler.dex.R8Task;
import com.tyron.builder.compiler.firebase.GenerateFirebaseConfigTask;
import com.tyron.builder.compiler.incremental.dex.IncrementalD8Task;
import com.tyron.builder.compiler.incremental.java.IncrementalJavaTask;
import com.tyron.builder.compiler.incremental.kotlin.IncrementalKotlinCompiler;
import com.tyron.builder.compiler.incremental.resource.IncrementalAapt2Task;
import com.tyron.builder.compiler.java.CheckLibrariesTask;
import com.tyron.builder.compiler.manifest.ManifestMergeTask;
import com.tyron.builder.compiler.symbol.MergeSymbolsTask;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.ModuleSettings;
import com.tyron.builder.project.api.AndroidModule;

import java.util.ArrayList;
import java.util.List;

public class AndroidAppBundleBuilder extends BuilderImpl<AndroidModule> {

    public AndroidAppBundleBuilder(AndroidModule project, ILogger logger) {
        super(project, logger);
    }

    @Override
    public List<Task<? super AndroidModule>> getTasks(BuildType type) {
        List<Task<? super AndroidModule>> tasks = new ArrayList<>();
        tasks.add(new CleanTask(getModule(), getLogger()));
        tasks.add(new CheckLibrariesTask(getModule(), getLogger()));
        tasks.add(new ManifestMergeTask(getModule(), getLogger()));
        tasks.add(new GenerateFirebaseConfigTask(getModule(), getLogger()));
        tasks.add(new IncrementalAapt2Task(getModule(), getLogger(), true));
        tasks.add(new MergeSymbolsTask(getModule(), getLogger()));
        tasks.add(new IncrementalKotlinCompiler(getModule(), getLogger()));
        tasks.add(new IncrementalJavaTask(getModule(), getLogger()));
        if (getModule().getSettings().getBoolean(ModuleSettings.USE_R8, false)) {
            tasks.add(new R8Task(getModule(), getLogger()));
        } else {
            tasks.add(new IncrementalD8Task(getModule(), getLogger()));
        }
        tasks.add(new AabTask(getModule(), getLogger()));
        return tasks;
    }
}
