package com.tyron.viewbinding.tool.processing.scopes;

import com.tyron.viewbinding.tool.store.Location;

import java.util.List;

/**
 * An item that is tight to locations in a source file.
 */
public interface LocationScopeProvider extends ScopeProvider {
    List<Location> provideScopeLocation();
}