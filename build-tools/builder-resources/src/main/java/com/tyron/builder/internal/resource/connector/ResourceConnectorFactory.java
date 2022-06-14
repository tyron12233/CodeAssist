package com.tyron.builder.internal.resource.connector;

import com.tyron.builder.authentication.Authentication;
import com.tyron.builder.internal.resource.transfer.ExternalResourceConnector;

import java.util.Set;

public interface ResourceConnectorFactory {
    Set<String> getSupportedProtocols();

    Set<Class<? extends Authentication>> getSupportedAuthentication();

    ExternalResourceConnector createResourceConnector(ResourceConnectorSpecification connectionDetails);
}
