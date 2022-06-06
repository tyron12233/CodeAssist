package com.tyron.builder.internal.authentication;

import com.google.common.collect.Maps;
import com.tyron.builder.authentication.Authentication;
import com.tyron.builder.internal.Cast;

import java.util.Collections;
import java.util.Map;

public class DefaultAuthenticationSchemeRegistry implements AuthenticationSchemeRegistry {
    Map<Class<? extends Authentication>, Class<? extends Authentication>> registeredSchemes = Maps.newHashMap();

    @Override
    public <T extends Authentication> void registerScheme(Class<T> type, final Class<? extends T> implementationType) {
        registeredSchemes.put(type, implementationType);
    }

    @Override
    public <T extends Authentication> Map<Class<T>, Class<? extends T>> getRegisteredSchemes() {
        return Collections.unmodifiableMap(Cast.uncheckedNonnullCast(registeredSchemes));
    }
}
