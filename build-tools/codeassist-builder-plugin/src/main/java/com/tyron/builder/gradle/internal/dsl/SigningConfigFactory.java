package com.tyron.builder.gradle.internal.dsl;

import com.android.annotations.NonNull;
import com.tyron.builder.gradle.internal.services.DslServices;
import com.tyron.builder.core.BuilderConstants;
import com.tyron.builder.signing.DefaultSigningConfig;
import java.io.File;
import org.gradle.api.NamedDomainObjectFactory;

/** Factory to create SigningConfig objects. */
public class SigningConfigFactory implements NamedDomainObjectFactory<SigningConfig> {
    @NonNull private final DslServices dslServices;
    private final File defaultDebugKeystoreLocation;

    public SigningConfigFactory(
            @NonNull DslServices dslServices, File defaultDebugKeystoreLocation) {
        this.dslServices = dslServices;
        this.defaultDebugKeystoreLocation = defaultDebugKeystoreLocation;
    }

    @Override
    @NonNull
    public SigningConfig create(@NonNull String name) {
        SigningConfig signingConfig = dslServices.newDecoratedInstance(SigningConfig.class, name, dslServices);
        if (BuilderConstants.DEBUG.equals(name)) {
            new DefaultSigningConfig.DebugSigningConfig(defaultDebugKeystoreLocation).copyToSigningConfig(signingConfig);
        }
        return signingConfig;
    }
}
