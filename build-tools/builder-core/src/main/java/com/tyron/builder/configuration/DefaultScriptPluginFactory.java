package com.tyron.builder.configuration;

import com.tyron.builder.api.initialization.dsl.ScriptHandler;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.SettingsInternal;
import com.tyron.builder.api.internal.initialization.ClassLoaderScope;
import com.tyron.builder.api.internal.initialization.ScriptHandlerInternal;
import com.tyron.builder.api.internal.plugins.PluginManagerInternal;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.groovy.scripts.BasicScript;
import com.tyron.builder.groovy.scripts.ScriptCompiler;
import com.tyron.builder.groovy.scripts.ScriptCompilerFactory;
import com.tyron.builder.groovy.scripts.ScriptRunner;
import com.tyron.builder.groovy.scripts.ScriptSource;
import com.tyron.builder.groovy.scripts.internal.BuildScriptData;
import com.tyron.builder.groovy.scripts.internal.CompileOperation;
import com.tyron.builder.internal.Actions;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.logging.LoggingManagerInternal;
import com.tyron.builder.internal.service.DefaultServiceRegistry;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.model.dsl.internal.transform.ClosureCreationInterceptingVerifier;
import com.tyron.builder.plugin.management.internal.PluginRequests;
import com.tyron.builder.plugin.management.internal.autoapply.AutoAppliedPluginHandler;
import com.tyron.builder.plugin.use.internal.PluginRequestApplicator;
import com.tyron.builder.plugin.use.internal.PluginsAwareScript;

public class DefaultScriptPluginFactory implements ScriptPluginFactory {

    private final ServiceRegistry scriptServices;
    private final ScriptCompilerFactory scriptCompilerFactory;
    private final Factory<LoggingManagerInternal> loggingFactoryManager;
    private final AutoAppliedPluginHandler autoAppliedPluginHandler;
    private final PluginRequestApplicator pluginRequestApplicator;
    private final CompileOperationFactory compileOperationFactory;
    private ScriptPluginFactory scriptPluginFactory;

    public DefaultScriptPluginFactory(ServiceRegistry scriptServices, ScriptCompilerFactory scriptCompilerFactory, Factory<LoggingManagerInternal> loggingFactoryManager,
                                      AutoAppliedPluginHandler autoAppliedPluginHandler, PluginRequestApplicator pluginRequestApplicator,
                                      CompileOperationFactory compileOperationFactory) {
        this.scriptServices = scriptServices;
        this.scriptCompilerFactory = scriptCompilerFactory;
        this.loggingFactoryManager = loggingFactoryManager;
        this.autoAppliedPluginHandler = autoAppliedPluginHandler;
        this.pluginRequestApplicator = pluginRequestApplicator;
        this.compileOperationFactory = compileOperationFactory;
        this.scriptPluginFactory = this;
    }

    public void setScriptPluginFactory(ScriptPluginFactory scriptPluginFactory) {
        this.scriptPluginFactory = scriptPluginFactory;
    }

    @Override
    public ScriptPlugin create(ScriptSource scriptSource, ScriptHandler scriptHandler, ClassLoaderScope targetScope, ClassLoaderScope baseScope, boolean topLevelScript) {
        return new ScriptPluginImpl(scriptSource, (ScriptHandlerInternal) scriptHandler, targetScope, baseScope, topLevelScript);
    }

    private class ScriptPluginImpl implements ScriptPlugin {
        private final ScriptSource scriptSource;
        private final ClassLoaderScope targetScope;
        private final ClassLoaderScope baseScope;
        private final ScriptHandlerInternal scriptHandler;
        private final boolean topLevelScript;

        public ScriptPluginImpl(ScriptSource scriptSource, ScriptHandlerInternal scriptHandler, ClassLoaderScope targetScope, ClassLoaderScope baseScope, boolean topLevelScript) {
            this.scriptSource = scriptSource;
            this.targetScope = targetScope;
            this.baseScope = baseScope;
            this.scriptHandler = scriptHandler;
            this.topLevelScript = topLevelScript;
        }

        @Override
        public ScriptSource getSource() {
            return scriptSource;
        }

        @Override
        public void apply(final Object target) {
            DefaultServiceRegistry services = new DefaultServiceRegistry(scriptServices);
            services.add(ScriptPluginFactory.class, scriptPluginFactory);
            services.add(ClassLoaderScope.class, baseScope);
            services.add(LoggingManagerInternal.class, loggingFactoryManager.create());
            services.add(ScriptHandler.class, scriptHandler);

            final ScriptTarget initialPassScriptTarget = initialPassTarget(target);

            ScriptCompiler compiler = scriptCompilerFactory.createCompiler(scriptSource);

            // Pass 1, extract plugin requests and plugin repositories and execute buildscript {}, ignoring (i.e. not even compiling) anything else
            CompileOperation<?> initialOperation = compileOperationFactory.getPluginsBlockCompileOperation(initialPassScriptTarget);
            Class<? extends BasicScript> scriptType = initialPassScriptTarget.getScriptClass();
            ScriptRunner<? extends BasicScript, ?> initialRunner = compiler.compile(scriptType, initialOperation, baseScope, Actions.doNothing());
            initialRunner.run(target, services);

            PluginRequests initialPluginRequests = getInitialPluginRequests(initialRunner);
            PluginRequests mergedPluginRequests = autoAppliedPluginHandler.mergeWithAutoAppliedPlugins(initialPluginRequests, target);

            PluginManagerInternal pluginManager = topLevelScript ? initialPassScriptTarget.getPluginManager() : null;
            pluginRequestApplicator.applyPlugins(mergedPluginRequests, scriptHandler, pluginManager, targetScope);

            // Pass 2, compile everything except buildscript {}, pluginManagement{}, and plugin requests, then run
            final ScriptTarget scriptTarget = secondPassTarget(target);
            scriptType = scriptTarget.getScriptClass();

            CompileOperation<BuildScriptData> operation = compileOperationFactory.getScriptCompileOperation(scriptSource, scriptTarget);

            final ScriptRunner<? extends BasicScript, BuildScriptData> runner = compiler.compile(scriptType, operation, targetScope, ClosureCreationInterceptingVerifier.INSTANCE);
            if (scriptTarget.getSupportsMethodInheritance() && runner.getHasMethods()) {
                scriptTarget.attachScript(runner.getScript());
            }
            if (!runner.getRunDoesSomething()) {
                return;
            }

            Runnable buildScriptRunner = () -> runner.run(target, services);

            boolean hasImperativeStatements = runner.getData().getHasImperativeStatements();
            scriptTarget.addConfiguration(buildScriptRunner, !hasImperativeStatements);
        }

        private ScriptTarget initialPassTarget(Object target) {
            return wrap(target, true /* isInitialPass */);
        }

        private ScriptTarget secondPassTarget(Object target) {
            return wrap(target, false /* isInitialPass */);
        }

        private ScriptTarget wrap(Object target, boolean isInitialPass) {
            if (target instanceof ProjectInternal && topLevelScript) {
                // Only use this for top level project scripts
                return new ProjectScriptTarget((ProjectInternal) target);
            }
            if (target instanceof GradleInternal && topLevelScript) {
                // Only use this for top level init scripts
                return new InitScriptTarget((GradleInternal) target);
            }
            if (target instanceof SettingsInternal && topLevelScript) {
                // Only use this for top level settings scripts
                if (isInitialPass) {
                    return new InitialPassSettingScriptTarget((SettingsInternal) target);
                } else {
                    return new SettingScriptTarget((SettingsInternal) target);
                }
            } else {
                return new DefaultScriptTarget(target);
            }
        }
    }

    // TODO This is not nice: work out a better way to collect the plugin requests from invoking the plugins block.
    private PluginRequests getInitialPluginRequests(ScriptRunner<? extends BasicScript, ?> initialRunner) {
        if (initialRunner.getRunDoesSomething()) {
            BasicScript script = initialRunner.getScript();
            if (script instanceof PluginsAwareScript) {
                return ((PluginsAwareScript) script).getPluginRequests();
            }
        }
        return PluginRequests.EMPTY;
    }
}
