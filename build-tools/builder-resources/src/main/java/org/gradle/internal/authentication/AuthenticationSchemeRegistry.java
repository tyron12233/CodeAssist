package org.gradle.internal.authentication;

import org.gradle.authentication.Authentication;

import java.util.Map;

public interface AuthenticationSchemeRegistry {
    <T extends Authentication> void registerScheme(Class<T> type, final Class<? extends T> implementationType);
    <T extends Authentication> Map<Class<T>, Class<? extends T>> getRegisteredSchemes();
}
