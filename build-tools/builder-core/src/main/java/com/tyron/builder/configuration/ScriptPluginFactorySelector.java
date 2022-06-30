package com.tyron.builder.configuration;

import com.tyron.builder.api.initialization.dsl.ScriptHandler;
import com.tyron.builder.api.internal.initialization.ClassLoaderScope;
import com.tyron.builder.configuration.internal.UserCodeApplicationContext;
import com.tyron.builder.groovy.scripts.ScriptSource;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.scripts.ScriptingLanguages;
import com.tyron.builder.scripts.ScriptingLanguage;

import java.util.List;

/**
 * Selects a {@link ScriptPluginFactory} suitable for handling a given build script based
 * on its file name. Build script file names ending in ".gradle" are supported by the
 * {@link DefaultScriptPluginFactory}. Other files are delegated to the first available
 * matching implementation of the {@link ScriptingLanguage} SPI. If no provider
 * implementations matches for a given file name, handling falls back to the
 * {@link DefaultScriptPluginFactory}. This approach allows users to name build scripts
 * with a suffix of choice, e.g. "build.groovy" or "my.build" instead of the typical
 * "build.gradle" while preserving default behaviour which is to fallback to Groovy support.
 *
 * This factory wraps each {@link ScriptPlugin} implementation in a {@link BuildOperationScriptPlugin}.
 *
 * @since 2.14
 */
public class ScriptPluginFactorySelector implements ScriptPluginFactory {

    /**
     * Scripting language ScriptPluginFactory instantiator.
     *
     * @since 4.0
     */
    public interface ProviderInstantiator {
        ScriptPluginFactory instantiate(String providerClassName);
    }

    /**
     * Default scripting language ScriptPluginFactory instantiator.
     *
     * @param instantiator the instantiator
     * @return the provider instantiator
     * @since 4.0
     */
    public static ProviderInstantiator defaultProviderInstantiatorFor(final Instantiator instantiator) {
        return new ProviderInstantiator() {

            @Override
            public ScriptPluginFactory instantiate(String providerClassName) {
                Class<?> providerClass = loadProviderClass(providerClassName);
                return (ScriptPluginFactory) instantiator.newInstance(providerClass);
            }

            private Class<?> loadProviderClass(String providerClassName) {
                try {
                    return getClass().getClassLoader().loadClass(providerClassName);
                } catch (ClassNotFoundException e) {
                    throw UncheckedException.throwAsUncheckedException(e);
                }
            }
        };
    }

    private final ScriptPluginFactory defaultScriptPluginFactory;
    private final ProviderInstantiator providerInstantiator;
    private final BuildOperationExecutor buildOperationExecutor;
    private final UserCodeApplicationContext userCodeApplicationContext;

    public ScriptPluginFactorySelector(ScriptPluginFactory defaultScriptPluginFactory,
                                       ProviderInstantiator providerInstantiator,
                                       BuildOperationExecutor buildOperationExecutor, UserCodeApplicationContext userCodeApplicationContext) {
        this.defaultScriptPluginFactory = defaultScriptPluginFactory;
        this.providerInstantiator = providerInstantiator;
        this.buildOperationExecutor = buildOperationExecutor;
        this.userCodeApplicationContext = userCodeApplicationContext;
    }

    @Override
    public ScriptPlugin create(ScriptSource scriptSource, ScriptHandler scriptHandler, ClassLoaderScope targetScope,
                               ClassLoaderScope baseScope, boolean topLevelScript) {
        ScriptPlugin scriptPlugin = scriptPluginFactoryFor(scriptSource.getFileName())
            .create(scriptSource, scriptHandler, targetScope, baseScope, topLevelScript);
        return new BuildOperationScriptPlugin(scriptPlugin, buildOperationExecutor, userCodeApplicationContext);
    }

    private ScriptPluginFactory scriptPluginFactoryFor(String fileName) {
        for (ScriptingLanguage scriptingLanguage : scriptingLanguages()) {
            if (fileName.endsWith(scriptingLanguage.getExtension())) {
                String provider = scriptingLanguage.getProvider();
                if (provider != null) {
                    return instantiate(provider);
                }
                return defaultScriptPluginFactory;
            }
        }
        return defaultScriptPluginFactory;
    }

    private List<ScriptingLanguage> scriptingLanguages() {
        return ScriptingLanguages.all();
    }

    private ScriptPluginFactory instantiate(String provider) {
        return providerInstantiator.instantiate(provider);
    }
}
