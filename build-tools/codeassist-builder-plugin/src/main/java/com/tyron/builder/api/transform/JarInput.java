package com.tyron.builder.api.transform;

import com.android.annotations.NonNull;
import java.util.Collection;

/**
 * A {@link QualifiedContent} of type jar.
 * <p>
 * This means the {@link #getFile()} is the jar file containing the content.
 * <p>
 * This also contains the incremental state of the jar file, if the transform is in incremental
 * mode through {@link #getStatus()}.
 * <p>
 * For a transform to run in incremental mode:
 * <ul>
 *     <li>{@link Transform#isIncremental()} must return <code>true</code></li>
 *     <li>The parameter <var>isIncremental</var> of
 *     {@link Transform#transform(Context, Collection, Collection, TransformOutputProvider, boolean)}
 *     must be <code>true</code>.</li>
 * </ul>
 *
 * If the transform is not in incremental mode, {@link #getStatus()} always returns
 * {@link Status#NOTCHANGED}.
 * @deprecated
 */
@Deprecated
public interface JarInput extends QualifiedContent {

    @NonNull
    Status getStatus();
}
