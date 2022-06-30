package com.tyron.builder.configuration.project;

import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.configuration.CompileOperationFactory;
import com.tyron.builder.configuration.ScriptTarget;
import com.tyron.builder.groovy.scripts.ScriptSource;
import com.tyron.builder.groovy.scripts.internal.BuildScriptData;
import com.tyron.builder.groovy.scripts.internal.BuildScriptDataSerializer;
import com.tyron.builder.groovy.scripts.internal.BuildScriptTransformer;
import com.tyron.builder.groovy.scripts.internal.CompileOperation;
import com.tyron.builder.groovy.scripts.internal.FactoryBackedCompileOperation;
import com.tyron.builder.groovy.scripts.internal.InitialPassStatementTransformer;
import com.tyron.builder.groovy.scripts.internal.NoDataCompileOperation;
import com.tyron.builder.groovy.scripts.internal.SubsetScriptTransformer;
import com.tyron.builder.api.internal.cache.StringInterner;

public class DefaultCompileOperationFactory implements CompileOperationFactory {
    private static final StringInterner INTERNER = new StringInterner();
    private static final String CLASSPATH_COMPILE_STAGE = "CLASSPATH";
    private static final String BODY_COMPILE_STAGE = "BODY";

    private final BuildScriptDataSerializer buildScriptDataSerializer = new BuildScriptDataSerializer();
    private final DocumentationRegistry documentationRegistry;

    public DefaultCompileOperationFactory(DocumentationRegistry documentationRegistry) {
        this.documentationRegistry = documentationRegistry;
    }

    public CompileOperation<?> getPluginsBlockCompileOperation(ScriptTarget initialPassScriptTarget) {
        InitialPassStatementTransformer initialPassStatementTransformer = new InitialPassStatementTransformer(initialPassScriptTarget, documentationRegistry);
        SubsetScriptTransformer initialTransformer = new SubsetScriptTransformer(initialPassStatementTransformer);
        String id = INTERNER.intern("cp_" + initialPassScriptTarget.getId());
        return new NoDataCompileOperation(id, CLASSPATH_COMPILE_STAGE, initialTransformer);
    }

    public CompileOperation<BuildScriptData> getScriptCompileOperation(ScriptSource scriptSource, ScriptTarget scriptTarget) {
        BuildScriptTransformer buildScriptTransformer = new BuildScriptTransformer(scriptSource, scriptTarget);
        String operationId = scriptTarget.getId();
        return new FactoryBackedCompileOperation<>(operationId, BODY_COMPILE_STAGE, buildScriptTransformer, buildScriptTransformer, buildScriptDataSerializer);
    }
}
