package com.tyron.builder.api.internal.tasks.compile.incremental;

import com.google.common.collect.Iterables;
import com.tyron.builder.api.internal.tasks.compile.CleaningJavaCompiler;
import com.tyron.builder.api.internal.tasks.compile.JavaCompileSpec;
import com.tyron.builder.api.internal.tasks.compile.incremental.recomp.CurrentCompilation;
import com.tyron.builder.api.internal.tasks.compile.incremental.recomp.CurrentCompilationAccess;
import com.tyron.builder.api.internal.tasks.compile.incremental.recomp.PreviousCompilation;
import com.tyron.builder.api.internal.tasks.compile.incremental.recomp.PreviousCompilationAccess;
import com.tyron.builder.api.internal.tasks.compile.incremental.recomp.PreviousCompilationData;
import com.tyron.builder.api.internal.tasks.compile.incremental.recomp.RecompilationSpec;
import com.tyron.builder.api.internal.tasks.compile.incremental.recomp.RecompilationSpecProvider;
import com.tyron.builder.internal.time.Time;
import com.tyron.builder.internal.time.Timer;
import com.tyron.builder.api.tasks.WorkResult;
import com.tyron.builder.api.tasks.WorkResults;
import com.tyron.builder.language.base.internal.compile.Compiler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Collection;
import java.util.Objects;

class SelectiveCompiler<T extends JavaCompileSpec> implements Compiler<T> {
    private static final Logger LOG = LoggerFactory.getLogger(SelectiveCompiler.class);
    private final CleaningJavaCompiler<T> cleaningCompiler;
    private final Compiler<T> rebuildAllCompiler;
    private final RecompilationSpecProvider recompilationSpecProvider;
    private final CurrentCompilationAccess classpathSnapshotter;
    private final PreviousCompilationAccess previousCompilationAccess;

    public SelectiveCompiler(
            CleaningJavaCompiler<T> cleaningJavaCompiler,
            Compiler<T> rebuildAllCompiler,
            RecompilationSpecProvider recompilationSpecProvider,
            CurrentCompilationAccess classpathSnapshotter,
            PreviousCompilationAccess previousCompilationAccess
    ) {
        this.cleaningCompiler = cleaningJavaCompiler;
        this.rebuildAllCompiler = rebuildAllCompiler;
        this.recompilationSpecProvider = recompilationSpecProvider;
        this.classpathSnapshotter = classpathSnapshotter;
        this.previousCompilationAccess = previousCompilationAccess;
    }

    @Override
    public WorkResult execute(T spec) {
        if (!recompilationSpecProvider.isIncremental()) {
            LOG.info("Full recompilation is required because no incremental change information is available. This is usually caused by clean builds or changing compiler arguments.");
            return rebuildAllCompiler.execute(spec);
        }
        File previousCompilationDataFile = Objects.requireNonNull(spec.getCompileOptions().getPreviousCompilationDataFile());
        if (!previousCompilationDataFile.exists()) {
            LOG.info("Full recompilation is required because no previous compilation result is available.");
            return rebuildAllCompiler.execute(spec);
        }
        if (spec.getSourceRoots().isEmpty()) {
            LOG.info("Full recompilation is required because the source roots could not be inferred.");
            return rebuildAllCompiler.execute(spec);
        }

        Timer clock = Time.startTimer();
        CurrentCompilation currentCompilation = new CurrentCompilation(spec, classpathSnapshotter);

        PreviousCompilationData previousCompilationData = previousCompilationAccess.readPreviousCompilationData(previousCompilationDataFile);
        PreviousCompilation previousCompilation = new PreviousCompilation(previousCompilationData);
        RecompilationSpec recompilationSpec = recompilationSpecProvider.provideRecompilationSpec(currentCompilation, previousCompilation);

        if (recompilationSpec.isFullRebuildNeeded()) {
            LOG.info("Full recompilation is required because {}. Analysis took {}.", recompilationSpec.getFullRebuildCause(), clock.getElapsed());
            return rebuildAllCompiler.execute(spec);
        }

        boolean cleanedOutput = recompilationSpecProvider.initializeCompilation(spec, recompilationSpec);

        if (Iterables.isEmpty(spec.getSourceFiles()) && spec.getClasses().isEmpty()) {
            LOG.info("None of the classes needs to be compiled! Analysis took {}. ", clock.getElapsed());
            return new RecompilationNotNecessary(previousCompilationData, recompilationSpec);
        }

        try {
            WorkResult result = recompilationSpecProvider.decorateResult(recompilationSpec, previousCompilationData, cleaningCompiler.getCompiler().execute(spec));
            return result.or(WorkResults.didWork(cleanedOutput));
        } finally {
            Collection<String> classesToCompile = recompilationSpec.getClassesToCompile();
            LOG.info("Incremental compilation of {} classes completed in {}.", classesToCompile.size(), clock.getElapsed());
            LOG.debug("Recompiled classes {}", classesToCompile);
        }
    }
}
