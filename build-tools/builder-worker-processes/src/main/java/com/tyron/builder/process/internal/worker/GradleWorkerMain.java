package com.tyron.builder.process.internal.worker;

import com.tyron.builder.internal.classloader.FilteringClassLoader;
import com.tyron.builder.internal.stream.EncodedStream;

import java.io.DataInputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * The main entry point for a worker process that is using the system ClassLoader strategy. Reads worker configuration and a serialized worker action from stdin,
 * sets up the worker ClassLoader, and then delegates to {@link com.tyron.builder.process.internal.worker.child.SystemApplicationClassLoaderWorker} to deserialize and execute the action.
 */
public class GradleWorkerMain {
    public void run() throws Exception {
        DataInputStream instr = new DataInputStream(new EncodedStream.EncodedInput(System.in));

        // Read shared packages
        int sharedPackagesCount = instr.readInt();
        List<String> sharedPackages = new ArrayList<String>(sharedPackagesCount);
        for (int i = 0; i < sharedPackagesCount; i++) {
            sharedPackages.add(instr.readUTF());
        }

        // Read worker implementation classpath
        int classPathLength = instr.readInt();
        URL[] implementationClassPath = new URL[classPathLength];
        for (int i = 0; i < classPathLength; i++) {
            String url = instr.readUTF();
            implementationClassPath[i] = new URL(url);
        }

        ClassLoader implementationClassLoader;
        if (classPathLength > 0) {
            // Set up worker ClassLoader
            FilteringClassLoader.Spec filteringClassLoaderSpec = new FilteringClassLoader.Spec();
            for (String sharedPackage : sharedPackages) {
                filteringClassLoaderSpec.allowPackage(sharedPackage);
            }
            FilteringClassLoader filteringClassLoader = new FilteringClassLoader(getClass().getClassLoader(), filteringClassLoaderSpec);
            implementationClassLoader = new URLClassLoader(implementationClassPath, filteringClassLoader);
        } else {
            // If no implementation classpath has been provided, just use the application classloader
            implementationClassLoader = getClass().getClassLoader();
        }

        @SuppressWarnings("unchecked")
        Class<? extends Callable<Void>> workerClass = (Class<? extends Callable<Void>>) implementationClassLoader.loadClass("com.tyron.builder.process.internal.worker.child.SystemApplicationClassLoaderWorker").asSubclass(Callable.class);
        Callable<Void> main = workerClass.getConstructor(DataInputStream.class).newInstance(instr);
        main.call();
    }

    public static void main(String[] args) {
        try {
            new GradleWorkerMain().run();
            System.exit(0);
        } catch (Throwable throwable) {
            throwable.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
