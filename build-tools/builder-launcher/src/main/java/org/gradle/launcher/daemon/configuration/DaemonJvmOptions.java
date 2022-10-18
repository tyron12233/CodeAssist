package org.gradle.launcher.daemon.configuration;

import com.google.common.collect.ImmutableSet;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.specs.Spec;
import org.gradle.cache.internal.HeapProportionalCacheSizer;
import org.gradle.process.internal.CurrentProcess;
import org.gradle.process.internal.JvmOptions;
import org.gradle.util.internal.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DaemonJvmOptions extends JvmOptions {

    public static final String SSL_KEYSTORE_KEY = "javax.net.ssl.keyStore";
    public static final String SSL_KEYSTOREPASSWORD_KEY = "javax.net.ssl.keyStorePassword";
    public static final String SSL_KEYSTORETYPE_KEY = "javax.net.ssl.keyStoreType";
    public static final String SSL_TRUSTSTORE_KEY = "javax.net.ssl.trustStore";
    public static final String SSL_TRUSTPASSWORD_KEY = "javax.net.ssl.trustStorePassword";
    public static final String SSL_TRUSTSTORETYPE_KEY = "javax.net.ssl.trustStoreType";

    public static final Set<String> IMMUTABLE_DAEMON_SYSTEM_PROPERTIES = ImmutableSet.of(
        SSL_KEYSTORE_KEY, SSL_KEYSTOREPASSWORD_KEY, SSL_KEYSTORETYPE_KEY, SSL_TRUSTPASSWORD_KEY, SSL_TRUSTSTORE_KEY, SSL_TRUSTSTORETYPE_KEY, HeapProportionalCacheSizer.CACHE_RESERVED_SYSTEM_PROPERTY
    );

    public DaemonJvmOptions(FileCollectionFactory fileCollectionFactory) {
        super(fileCollectionFactory);
        final JvmOptions currentProcessJvmOptions = new CurrentProcess(fileCollectionFactory).getJvmOptions();
        systemProperties(currentProcessJvmOptions.getImmutableSystemProperties());
        handleDaemonImmutableProperties(currentProcessJvmOptions.getMutableSystemProperties());
    }

    private void handleDaemonImmutableProperties(Map<String, Object> systemProperties) {
        for (Map.Entry<String, ?> entry : systemProperties.entrySet()) {
            if(IMMUTABLE_DAEMON_SYSTEM_PROPERTIES.contains(entry.getKey())){
                immutableSystemProperties.put(entry.getKey(), entry.getValue());
            }
        }
    }

    public Map<String, Object> getImmutableDaemonProperties() {
        return CollectionUtils.filter(immutableSystemProperties, new Spec<Map.Entry<String, Object>>() {
            @Override
            public boolean isSatisfiedBy(Map.Entry<String, Object> element) {
                return IMMUTABLE_DAEMON_SYSTEM_PROPERTIES.contains(element.getKey());
            }
        });
    }

    @Override
    public void systemProperty(String name, Object value) {
        if (IMMUTABLE_DAEMON_SYSTEM_PROPERTIES.contains(name)) {
            immutableSystemProperties.put(name, value);
        } else {
            super.systemProperty(name, value);
        }
    }

    public List<String> getAllSingleUseImmutableJvmArgs() {
        List<String> immutableDaemonParameters = new ArrayList<String>();
        formatSystemProperties(getImmutableDaemonProperties(), immutableDaemonParameters);
        final List<String> jvmArgs = getAllImmutableJvmArgs();
        jvmArgs.removeAll(immutableDaemonParameters);
        return jvmArgs;
    }
}
