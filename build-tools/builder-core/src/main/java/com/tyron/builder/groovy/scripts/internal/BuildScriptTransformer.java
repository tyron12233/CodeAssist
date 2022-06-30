package com.tyron.builder.groovy.scripts.internal;

import com.tyron.builder.configuration.ScriptTarget;
import com.tyron.builder.groovy.scripts.ScriptSource;
import com.tyron.builder.groovy.scripts.Transformer;
import com.tyron.builder.internal.Factory;

import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.CompilationUnit;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

public class BuildScriptTransformer implements Transformer, Factory<BuildScriptData> {

    private final Predicate<? super Statement> filter;
    private final ScriptSource scriptSource;

    private final ImperativeStatementDetectingTransformer imperativeStatementDetectingTransformer = new ImperativeStatementDetectingTransformer();

    public BuildScriptTransformer(ScriptSource scriptSource, ScriptTarget scriptTarget) {
        final List<String> blocksToIgnore = Arrays.asList(scriptTarget.getClasspathBlockName(), InitialPassStatementTransformer.PLUGINS, InitialPassStatementTransformer.PLUGIN_MANAGEMENT);
        this.filter = (Predicate<Statement>) statement -> AstUtils.detectScriptBlock(statement, blocksToIgnore) != null;
        this.scriptSource = scriptSource;
    }

    @Override
    public void register(CompilationUnit compilationUnit) {
        new FilteringScriptTransformer(filter).register(compilationUnit);
        new TaskDefinitionScriptTransformer().register(compilationUnit);
        new FixMainScriptTransformer().register(compilationUnit);
        new StatementLabelsScriptTransformer().register(compilationUnit);
        new ModelBlockTransformer(scriptSource.getDisplayName(), scriptSource.getResource().getLocation().getURI()).register(compilationUnit);
         imperativeStatementDetectingTransformer.register(compilationUnit);
    }

    @Override
    public BuildScriptData create() {
        return new BuildScriptData(imperativeStatementDetectingTransformer.isImperativeStatementDetected());
    }
}
