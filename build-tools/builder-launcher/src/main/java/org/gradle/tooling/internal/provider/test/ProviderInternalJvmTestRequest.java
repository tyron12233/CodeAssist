package org.gradle.tooling.internal.provider.test;

import org.gradle.tooling.internal.protocol.test.InternalJvmTestRequest;

import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Objects;

public class ProviderInternalJvmTestRequest implements InternalJvmTestRequest, Serializable {
    private final String className;
    private final String methodName;

    public ProviderInternalJvmTestRequest(String className, @Nullable String methodName) {
        this.className = className;
        this.methodName = methodName;
    }

    @Override
    public String getClassName() {
        return className;
    }

    @Override
    public String getMethodName() {
        return methodName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ProviderInternalJvmTestRequest that = (ProviderInternalJvmTestRequest) o;

        if (!Objects.equals(className, that.className)) {
            return false;
        }
        return Objects.equals(methodName, that.methodName);

    }

    @Override
    public int hashCode() {
        int result = className != null ? className.hashCode() : 0;
        result = 31 * result + (methodName != null ? methodName.hashCode() : 0);
        return result;
    }
}
