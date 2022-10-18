package com.android.aaptcompiler;

/**
 * Used to access methods in {@link NamespaceContext} since they are internal
 */
@SuppressWarnings("KotlinInternalInJava")
public class NamespaceContextAccessor {

    public static void pop(NamespaceContext namespaceContext, String string) {
        namespaceContext.pop$aaptcompiler(string);
    }

    public static void push(NamespaceContext namespaceContext, String namespace, String uri) {
        namespaceContext.push$aaptcompiler(namespace, uri);
    }
}
