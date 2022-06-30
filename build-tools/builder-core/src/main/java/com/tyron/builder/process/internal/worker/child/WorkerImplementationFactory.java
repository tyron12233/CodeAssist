package com.tyron.builder.process.internal.worker.child;

import com.tyron.builder.internal.remote.Address;
import com.tyron.builder.process.internal.JavaExecHandleBuilder;
import com.tyron.builder.process.internal.worker.WorkerProcessBuilder;

import java.net.URL;
import java.util.List;

public interface WorkerImplementationFactory {
    /**
     * Configures the Java command that will be used to launch the child process.
     */
    void prepareJavaCommand(long workerId, String displayName, WorkerProcessBuilder processBuilder, List<URL> implementationClassPath, List<URL> implementationModulePath, Address serverAddress, JavaExecHandleBuilder execSpec, boolean publishProcessInfo);
}
