package com.tyron.builder.groovy.scripts;

import com.tyron.builder.api.GradleScriptException;
import com.tyron.builder.internal.service.ServiceRegistry;

/**
 * Executes a script of type T.
 */
public interface ScriptRunner<T extends Script, M> {
    /**
     * Returns the script which will be executed by this runner. This method is relatively expensive.
     *
     * @return the script.
     */
    T getScript();

    /**
     * Returns the data extracted at compilation time.
     */
    M getData();

    /**
     * Returns true when the script will run some code when executed. Returns false for a script whose `run()` method is effectively empty.
     */
    boolean getRunDoesSomething();

    /**
     * Returns true when the script defines some methods.
     */
    boolean getHasMethods();

    /**
     * Executes the script. This is generally more efficient than using {@link #getScript()}.
     *
     * @throws GradleScriptException On execution failure.
     */
    void run(Object target, ServiceRegistry scriptServices) throws GradleScriptException;
}
