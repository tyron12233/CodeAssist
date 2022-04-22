package com.tyron.builder.initialization.exception;

import com.tyron.builder.api.ProjectConfigurationException;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.exceptions.Contextual;
import com.tyron.builder.internal.reflect.service.ServiceCreationException;
import com.tyron.builder.api.tasks.TaskExecutionException;
import com.tyron.builder.internal.exceptions.LocationAwareException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class DefaultExceptionAnalyser implements ExceptionCollector {

    public DefaultExceptionAnalyser(ListenerManager listenerManager) {

    }

    private Throwable transform(Throwable exception) {
        Throwable actualException = findDeepestRootException(exception);
        if (actualException instanceof LocationAwareException) {
            return actualException;
        }

        String source = null;
        Integer lineNumber = null;
//
//        // TODO: remove these special cases
//        if (actualException instanceof ScriptCompilationException) {
//            ScriptCompilationException scriptCompilationException = (ScriptCompilationException) actualException;
//            source = scriptCompilationException.getScriptSource();
//            lineNumber = scriptCompilationException.getLineNumber();
//        }
//
//        if (source == null) {
//            for (
//                    Throwable currentException = actualException;
//                    currentException != null;
//                    currentException = currentException.getCause()
//            ) {
//                for (StackTraceElement element : currentException.getStackTrace()) {
//                    if (element.getLineNumber() >= 0 && scripts.containsKey(element.getFileName())) {
//                        source = scripts.get(element.getFileName());
//                        lineNumber = element.getLineNumber();
//                        break;
//                    }
//                }
//            }
//        }

        return actualException;
    }

    private Throwable findDeepestRootException(Throwable exception) {
        // TODO: fix the way we work out which exception is important: TaskExecutionException is
        //  not always the most helpful
        Throwable locationAware = null;
        Throwable result = null;
        Throwable contextMatch = null;
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof LocationAwareException) {
                locationAware = current;
//                            current instanceof GradleScriptException ||
            } else if (current instanceof TaskExecutionException) {
                result = current;
            } else if (contextMatch == null &&
                       current.getClass().getAnnotation(Contextual.class) != null) {
                contextMatch = current;
            }
        }
        if (locationAware != null) {
            return locationAware;
        } else if (result != null) {
            return result;
        } else if (contextMatch != null) {
            return contextMatch;
        } else {
            return exception;
        }
    }

    @Override
    public void collectFailures(Throwable exception, Collection<? super Throwable> failures) {
        if (exception instanceof ProjectConfigurationException) {
            ProjectConfigurationException projectConfigurationException =
                    (ProjectConfigurationException) exception;
            List<Throwable> additionalFailures = new ArrayList<>();
            for (Throwable cause : projectConfigurationException.getCauses()) {
                // TODO: remove this special case
//                if (cause instanceof GradleScriptException) {
//                    failures.add(transform(cause));
//                } else {
                additionalFailures.add(cause);
//                }
            }
            if (!additionalFailures.isEmpty()) {
                projectConfigurationException.initCauses(additionalFailures);
                failures.add(transform(projectConfigurationException));
            }
        } else if (exception instanceof ServiceCreationException) {
            failures.add(transform(new InitializationException(exception)));
        } else {
            failures.add(transform(exception));
        }
    }
}