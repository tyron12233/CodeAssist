package org.gradle.groovy.scripts.internal;

import org.gradle.api.GradleScriptException;
import org.gradle.groovy.scripts.Script;
import org.gradle.groovy.scripts.ScriptRunner;
import org.gradle.groovy.scripts.ScriptSource;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.scripts.ScriptExecutionListener;

public class DefaultScriptRunnerFactory implements ScriptRunnerFactory {
    private final ScriptExecutionListener listener;
    private final Instantiator instantiator;

    public DefaultScriptRunnerFactory(ScriptExecutionListener listener, Instantiator instantiator) {
        this.listener = listener;
        this.instantiator = instantiator;
    }

    @Override
    public <T extends Script, M> ScriptRunner<T, M> create(CompiledScript<T, M> script, ScriptSource source, ClassLoader contextClassLoader) {
        return new ScriptRunnerImpl<T, M>(script, source, contextClassLoader);
    }

    private class ScriptRunnerImpl<T extends Script, M> implements ScriptRunner<T, M> {
        private final ScriptSource source;
        private final ClassLoader contextClassLoader;
        private T script;
        private final CompiledScript<T, M> compiledScript;

        public ScriptRunnerImpl(CompiledScript<T, M> compiledScript, ScriptSource source, ClassLoader contextClassLoader) {
            this.compiledScript = compiledScript;
            this.source = source;
            this.contextClassLoader = contextClassLoader;
        }

        @Override
        public T getScript() {
            if (script == null) {
                Class<? extends T> scriptClass = compiledScript.loadClass();
                script = instantiator.newInstance(scriptClass);
                script.setScriptSource(source);
                script.setContextClassloader(contextClassLoader);
                listener.onScriptClassLoaded(source, scriptClass);
            }
            return script;
        }

        @Override
        public M getData() {
            return compiledScript.getData();
        }

        @Override
        public boolean getRunDoesSomething() {
            return compiledScript.getRunDoesSomething();
        }

        @Override
        public boolean getHasMethods() {
            return compiledScript.getHasMethods();
        }

        @Override
        public void run(Object target, ServiceRegistry scriptServices) throws GradleScriptException {
            if (!compiledScript.getRunDoesSomething()) {
                return;
            }

            ClassLoader originalLoader = Thread.currentThread().getContextClassLoader();
            T script = getScript();
            script.init(target, scriptServices);
            Thread.currentThread().setContextClassLoader(script.getContextClassloader());
            script.getStandardOutputCapture().start();
            try {
                script.run();
            } catch (Throwable e) {
                throw new GradleScriptException(String.format("A problem occurred evaluating %s.", script), e);
            } finally {
                script.getStandardOutputCapture().stop();
                Thread.currentThread().setContextClassLoader(originalLoader);
            }
        }
    }
}
