package org.gradle.internal.resource.connector;

import org.gradle.authentication.Authentication;
import org.gradle.internal.resource.transfer.ExternalResourceConnector;

import java.util.Set;

public interface ResourceConnectorFactory {
    Set<String> getSupportedProtocols();

    Set<Class<? extends Authentication>> getSupportedAuthentication();

    ExternalResourceConnector createResourceConnector(ResourceConnectorSpecification connectionDetails);
}
