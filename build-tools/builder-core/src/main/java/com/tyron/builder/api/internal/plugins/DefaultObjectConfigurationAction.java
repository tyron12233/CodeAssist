package com.tyron.builder.api.internal.plugins;

import com.tyron.builder.api.InvalidUserCodeException;
import com.tyron.builder.api.Plugin;
import com.tyron.builder.api.initialization.dsl.ScriptHandler;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.internal.initialization.ClassLoaderScope;
import com.tyron.builder.api.internal.initialization.ScriptHandlerFactory;
import com.tyron.builder.api.plugins.ObjectConfigurationAction;
import com.tyron.builder.api.plugins.PluginAware;
import com.tyron.builder.api.resources.TextResourceFactory;
import com.tyron.builder.configuration.ScriptPlugin;
import com.tyron.builder.configuration.ScriptPluginFactory;
import com.tyron.builder.groovy.scripts.ScriptSource;
import com.tyron.builder.groovy.scripts.TextResourceScriptSource;
import com.tyron.builder.internal.resource.TextResource;
import com.tyron.builder.internal.resource.TextUriResourceLoader;
import com.tyron.builder.internal.verifier.HttpRedirectVerifier;
import com.tyron.builder.internal.verifier.HttpRedirectVerifierFactory;
import com.tyron.builder.util.GUtil;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;

public class DefaultObjectConfigurationAction implements ObjectConfigurationAction {

    private final FileResolver resolver;
    private final ScriptPluginFactory configurerFactory;
    private final ScriptHandlerFactory scriptHandlerFactory;
    private final Set<Object> targets = new LinkedHashSet<Object>();
    private final Set<Runnable> actions = new LinkedHashSet<Runnable>();
    private final ClassLoaderScope classLoaderScope;
    private final TextUriResourceLoader.Factory textUriFileResourceLoaderFactory;
    private final Object defaultTarget;

    public DefaultObjectConfigurationAction(FileResolver resolver, ScriptPluginFactory configurerFactory,
                                            ScriptHandlerFactory scriptHandlerFactory, ClassLoaderScope classLoaderScope,
                                            TextUriResourceLoader.Factory textUriFileResourceLoaderFactory, Object defaultTarget) {
        this.resolver = resolver;
        this.configurerFactory = configurerFactory;
        this.scriptHandlerFactory = scriptHandlerFactory;
        this.classLoaderScope = classLoaderScope;
        this.textUriFileResourceLoaderFactory = textUriFileResourceLoaderFactory;
        this.defaultTarget = defaultTarget;
    }

    @Override
    public ObjectConfigurationAction to(Object... targets) {
        GUtil.flatten(targets, this.targets);
        return this;
    }

    @Override
    public ObjectConfigurationAction from(final Object script) {
        actions.add(new Runnable() {
            @Override
            public void run() {
                applyScript(script);
            }
        });
        return this;
    }

    @Override
    public ObjectConfigurationAction plugin(final Class<? extends Plugin> pluginClass) {
        actions.add(new Runnable() {
            @Override
            public void run() {
                applyPlugin(pluginClass);
            }
        });
        return this;
    }

    @Override
    public ObjectConfigurationAction plugin(final String pluginId) {
        actions.add(new Runnable() {
            @Override
            public void run() {
                applyType(pluginId);
            }
        });
        return this;
    }

    @Override
    public ObjectConfigurationAction type(final Class<?> pluginClass) {
        actions.add(new Runnable() {
            @Override
            public void run() {
                applyType(pluginClass);
            }
        });
        return this;
    }

    private HttpRedirectVerifier createHttpRedirectVerifier(URI scriptUri) {
        return HttpRedirectVerifierFactory.create(
            scriptUri,
            false,
            () -> {
                String message =
                    "Applying script plugins from insecure URIs, without explicit opt-in, is unsupported. "; // +
//                        String.format("The provided URI '%s' uses an insecure protocol (HTTP). ", scriptUri) +
//                        String.format("Use '%s' instead or try 'apply from: resources.text.fromInsecureUri(\"%s\")' to fix this. ", GUtil.toSecureUrl(scriptUri), scriptUri) +
//                        Documentation
//                            .dslReference(TextResourceFactory.class, "fromInsecureUri(java.lang.Object)")
//                            .consultDocumentationMessage();
                throw new InvalidUserCodeException(message);
            },
            redirect -> {
                String message =
                    "Applying script plugins from an insecure redirect, without explicit opt-in, is unsupported. ";// +
//                        "Switch to HTTPS or use TextResourceFactory.fromInsecureUri(Object) to fix this. " +
//                        String.format("'%s' redirects to insecure '%s'. ", scriptUri, redirect) +
//                        Documentation
//                            .dslReference(TextResourceFactory.class, "fromInsecureUri(java.lang.Object)")
//                            .consultDocumentationMessage();
                throw new InvalidUserCodeException(message);
            }
        );
    }

    private void applyScript(Object script) {
        URI scriptUri = resolver.resolveUri(script);
        TextResource resource;
        if (script instanceof TextResource) {
            resource = (TextResource) script;
        } else {
            HttpRedirectVerifier redirectVerifier = createHttpRedirectVerifier(scriptUri);
            resource = textUriFileResourceLoaderFactory.create(redirectVerifier).loadUri("script", scriptUri);
        }
        ScriptSource scriptSource = new TextResourceScriptSource(resource);
        ClassLoaderScope classLoaderScopeChild = classLoaderScope.createChild("script-" + scriptUri.toString());
        ScriptHandler scriptHandler = scriptHandlerFactory.create(scriptSource, classLoaderScopeChild);
        ScriptPlugin configurer = configurerFactory.create(scriptSource, scriptHandler, classLoaderScopeChild, classLoaderScope, false);
        for (Object target : targets) {
            configurer.apply(target);
        }
    }

    private void applyPlugin(Class<? extends Plugin> pluginClass) {
        applyType(pluginClass);
    }

    private void applyType(String pluginId) {
        for (Object target : targets) {
            if (target instanceof PluginAware) {
                ((PluginAware) target).getPluginManager().apply(pluginId);
            } else {
                throw new UnsupportedOperationException(String.format("Cannot apply plugin with id '%s' to '%s' (class: %s) as it does not implement PluginAware", pluginId, target.toString(), target.getClass().getName()));
            }
        }
    }

    private void applyType(Class<?> pluginClass) {
        for (Object target : targets) {
            if (target instanceof PluginAware) {
                ((PluginAware) target).getPluginManager().apply(pluginClass);
            } else {
                throw new UnsupportedOperationException(String.format("Cannot apply plugin of class '%s' to '%s' (class: %s) as it does not implement PluginAware", pluginClass.getName(), target.toString(), target.getClass().getName()));
            }
        }
    }

    public void execute() {
        if (targets.isEmpty()) {
            to(defaultTarget);
        }

        for (Runnable action : actions) {
            action.run();
        }
    }
}
