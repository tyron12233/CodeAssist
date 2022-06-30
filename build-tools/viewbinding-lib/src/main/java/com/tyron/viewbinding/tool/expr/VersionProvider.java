package com.tyron.viewbinding.tool.expr;
/**
 * If an object can be invalidated, it is responsible to provide a version number.
 * This number is used when caching values in code generation. (like flags)
 */
public interface VersionProvider {
    int getVersion();
}
