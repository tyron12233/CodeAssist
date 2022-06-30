package com.tyron.builder.internal.authentication;

import com.tyron.builder.authentication.Authentication;

import java.util.Map;

public interface AuthenticationSchemeRegistry {
    <T extends Authentication> void registerScheme(Class<T> type, final Class<? extends T> implementationType);
    <T extends Authentication> Map<Class<T>, Class<? extends T>> getRegisteredSchemes();
}
