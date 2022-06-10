package org.gradle.internal.resource.transfer;

import org.gradle.api.resources.ResourceException;
import org.gradle.internal.resource.ExternalResource;
import org.gradle.internal.resource.ExternalResourceName;

import javax.annotation.Nullable;
import java.util.List;

/**
 * You should use {@link ExternalResource} instead of this type.
 */
public interface ExternalResourceLister {

    /**
     * Lists the direct children of the parent resource
     *
     * @param parent the resource to list from
     * @return A list of the direct children of the <code>parent</code>, null when the resource does not exist.
     */
    @Nullable
    List<String> list(ExternalResourceName parent) throws ResourceException;

}
