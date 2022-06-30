package com.tyron.viewbinding.tool.processing.scopes;

/**
 * An item that is tight to a source file.
 */
public interface FileScopeProvider extends ScopeProvider {
    String provideScopeFilePath();
}