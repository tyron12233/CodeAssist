package org.gradle.configuration.project;

import org.gradle.api.internal.DocumentationRegistry;
import org.gradle.configuration.CompileOperationFactory;
import org.gradle.configuration.ScriptTarget;
import org.gradle.groovy.scripts.ScriptSource;
import org.gradle.groovy.scripts.internal.BuildScriptData;
import org.gradle.groovy.scripts.internal.BuildScriptDataSerializer;
import org.gradle.groovy.scripts.internal.BuildScriptTransformer;
import org.gradle.groovy.scripts.internal.CompileOperation;
import org.gradle.groovy.scripts.internal.FactoryBackedCompileOperation;
import org.gradle.groovy.scripts.internal.InitialPassStatementTransformer;
import org.gradle.groovy.scripts.internal.NoDataCompileOperation;
import org.gradle.groovy.scripts.internal.SubsetScriptTransformer;
import org.gradle.api.internal.cache.StringInterner;

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
