package com.tyron.builder.workers.internal;

import com.tyron.builder.api.tasks.WorkResult;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.workers.WorkAction;

import javax.inject.Inject;
import java.util.concurrent.Callable;

import static com.tyron.builder.internal.classloader.ClassLoaderUtils.classFromContextLoader;

/**
 * This is used to bridge between the "old" worker api with untyped parameters and the typed
 * parameter api.  It allows us to maintain backwards compatibility at the api layer, but use
 * only typed parameters under the covers.  This can be removed once the old api is retired.
 */
public class AdapterWorkAction implements WorkAction<AdapterWorkParameters>, ProvidesWorkResult {
    private final AdapterWorkParameters parameters;
    private final Instantiator instantiator;
    private DefaultWorkResult workResult;

    @Inject
    public AdapterWorkAction(AdapterWorkParameters parameters, Instantiator instantiator) {
        this.parameters = parameters;
        this.instantiator = instantiator;
    }

    @Override
    public AdapterWorkParameters getParameters() {
        return parameters;
    }

    @Override
    public void execute() {
        AdapterWorkParameters parameters = getParameters();
        String implementationClassName = parameters.getImplementationClassName();
        Class<?> actionClass = classFromContextLoader(implementationClassName);

        Object action = instantiator.newInstance(actionClass, parameters.getParams());
        if (action instanceof Runnable) {
            ((Runnable) action).run();
            workResult = DefaultWorkResult.SUCCESS;
        } else if (action instanceof Callable) {
            Object result;
            try {
                result = ((Callable<?>) action).call();
                if (result instanceof DefaultWorkResult) {
                    workResult = (DefaultWorkResult) result;
                } else if (result instanceof WorkResult) {
                    workResult = new DefaultWorkResult(((WorkResult) result).getDidWork(), null);
                } else {
                    throw new IllegalArgumentException("Worker actions must return a WorkResult.");
                }
            } catch (Exception e) {
                workResult = new DefaultWorkResult(true, e);
            }
        } else {
            throw new IllegalArgumentException("Worker actions must either implement Runnable or Callable<WorkResult>.");
        }
    }

    @Override
    public DefaultWorkResult getWorkResult() {
        return workResult;
    }
}
