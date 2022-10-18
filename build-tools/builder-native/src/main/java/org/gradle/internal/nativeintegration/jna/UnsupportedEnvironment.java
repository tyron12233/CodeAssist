package org.gradle.internal.nativeintegration.jna;

import com.tyron.common.TestUtil;

import org.gradle.internal.nativeintegration.NativeIntegrationException;
import org.gradle.internal.nativeintegration.NativeIntegrationUnavailableException;
import org.gradle.internal.nativeintegration.ProcessEnvironment;
import org.gradle.internal.nativeintegration.EnvironmentModificationResult;
import org.gradle.internal.os.OperatingSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.sql.Ref;
import java.util.Map;

public class UnsupportedEnvironment implements ProcessEnvironment {
    private static final Logger LOGGER = LoggerFactory.getLogger(UnsupportedEnvironment.class);

    private final Long pid;

    public UnsupportedEnvironment() {
        pid = extractPIDFromRuntimeMXBeanName();
    }

    /**
     * The default format of the name of the Runtime MX bean is PID@HOSTNAME.
     * The PID is parsed assuming that is the format.
     *
     * This works on Solaris and should work with any Java VM
     */
    private Long extractPIDFromRuntimeMXBeanName() {
        if (TestUtil.isDalvik()) {
            try {
                return (Long) Class.forName("android.os.Process").getDeclaredMethod("myPid").invoke(null);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
        Long pid = null;
        String runtimeMXBeanName = ManagementFactory.getRuntimeMXBean().getName();
        int separatorPos = runtimeMXBeanName.indexOf('@');
        if (separatorPos > -1) {
            try {
                pid = Long.parseLong(runtimeMXBeanName.substring(0, separatorPos));
            } catch (NumberFormatException e) {
                LOGGER.debug("Native-platform process: failed to parse PID from Runtime MX bean name: " + runtimeMXBeanName);
            }
        } else {
            LOGGER.debug("Native-platform process: failed to parse PID from Runtime MX bean name");
        }
        return pid;
    }

    @Override
    public EnvironmentModificationResult maybeSetEnvironment(Map<String, String> source) {
        return EnvironmentModificationResult.UNSUPPORTED_ENVIRONMENT;
    }

    @Override
    public void removeEnvironmentVariable(String name) throws NativeIntegrationException {
        throw notSupported();
    }

    @Override
    public EnvironmentModificationResult maybeRemoveEnvironmentVariable(String name) {
        return EnvironmentModificationResult.UNSUPPORTED_ENVIRONMENT;
    }

    @Override
    public void setEnvironmentVariable(String name, String value) throws NativeIntegrationException {
        throw notSupported();
    }

    @Override
    public EnvironmentModificationResult maybeSetEnvironmentVariable(String name, String value) {
        return EnvironmentModificationResult.UNSUPPORTED_ENVIRONMENT;
    }

    @Override
    public File getProcessDir() throws NativeIntegrationException {
        throw notSupported();
    }

    @Override
    public void setProcessDir(File processDir) throws NativeIntegrationException {
        throw notSupported();
    }

    @Override
    public boolean maybeSetProcessDir(File processDir) {
        return false;
    }

    @Override
    public Long getPid() throws NativeIntegrationException {
        if (pid != null) {
            return pid;
        }
        throw notSupported();
    }

    @Override
    public Long maybeGetPid() {
        return pid;
    }

    @Override
    public boolean maybeDetachProcess() {
        return false;
    }

    @Override
    public void detachProcess() {
        throw notSupported();
    }

    private NativeIntegrationException notSupported() {
        return new NativeIntegrationUnavailableException("We don't support this operating system: " + OperatingSystem.current());
    }
}
