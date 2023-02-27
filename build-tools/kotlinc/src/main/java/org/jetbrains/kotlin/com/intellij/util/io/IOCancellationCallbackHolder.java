package org.jetbrains.kotlin.com.intellij.util.io;

import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class IOCancellationCallbackHolder {

    private static IOCancellationCallback usedIoCallback;

    public static IOCancellationCallback getUsedIoCallback() {
        if (usedIoCallback == null) {
            usedIoCallback = loadSingleCallback();
        }
        return usedIoCallback;
    }

    private static IOCancellationCallback loadSingleCallback() {
        ServiceLoader<IOCancellationCallback> serviceLoader =
                ServiceLoader.load(IOCancellationCallback.class,
                        IOCancellationCallback.class.getClassLoader());
        List<IOCancellationCallback> services = StreamSupport.stream(serviceLoader.spliterator(), false).collect(
                Collectors.toList());
        if (services.size() > 1) {
            throw new IllegalStateException("");
        }

        return services.stream().findFirst().orElse(new IOCancellationCallback() {
            @Override
            public void checkCancelled() throws ProcessCanceledException {

            }

            @Override
            public void interactWithUI() {

            }
        });
    }

    public static void checkCancelled() {
        getUsedIoCallback().checkCancelled();
    }

    public static void interactWithUI() {
        getUsedIoCallback().interactWithUI();
    }
}
