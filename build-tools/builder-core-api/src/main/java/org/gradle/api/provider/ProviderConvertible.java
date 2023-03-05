package org.gradle.api.provider;

import org.gradle.api.Incubating;
import org.gradle.internal.HasInternalProtocol;

/**
 * An object that can be converted to a {@link Provider}.
 *
 * @param <T> Type of value represented by provider
 * @since 7.3
 */
@Incubating
@HasInternalProtocol
//@NonExtensible
public interface ProviderConvertible<T> {

    /**
     * Returns a {@link Provider} from this object.
     */
    Provider<T> asProvider();

}
