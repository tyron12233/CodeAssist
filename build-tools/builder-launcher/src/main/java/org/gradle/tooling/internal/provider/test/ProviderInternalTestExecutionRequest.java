package org.gradle.tooling.internal.provider.test;

import org.gradle.tooling.internal.protocol.events.InternalTestDescriptor;
import org.gradle.tooling.internal.protocol.test.InternalDebugOptions;
import org.gradle.tooling.internal.protocol.test.InternalJvmTestRequest;
import org.gradle.tooling.internal.protocol.test.InternalTestPatternSpec;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * @since 2.7-rc-1
 */
public interface ProviderInternalTestExecutionRequest {
    Collection<InternalTestDescriptor> getTestExecutionDescriptors();
    Collection<String> getTestClassNames();
    Collection<InternalJvmTestRequest> getInternalJvmTestRequests(Collection<InternalJvmTestRequest> defaults);

    /**
     * @since 5.6
     */
    InternalDebugOptions getDebugOptions(InternalDebugOptions defaults);

    /**
     * @since 6.1
     */
    Map<String, List<InternalJvmTestRequest>> getTaskAndTests(Map<String, List<InternalJvmTestRequest>> defaults);

    /**
     * @since 7.6
     */
    List<String> getTasks(List<String> defaults);

    /**
     * @since 7.6
     */
    boolean isRunDefaultTasks(boolean dafaults);

    /**
     * @since 7.6
     */
    List<InternalTestPatternSpec> getTestPatternSpecs(List<InternalTestPatternSpec> defaults);
}