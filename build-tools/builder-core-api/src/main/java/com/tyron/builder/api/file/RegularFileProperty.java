package com.tyron.builder.api.file;

import com.tyron.builder.api.provider.Provider;

import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * Represents some configurable regular file location, whose value is mutable.
 *
 * <p>
 * You can create a {@link RegularFileProperty} using {@link ObjectFactory#fileProperty()}.
 * </p>
 *
 * <p><b>Note:</b> This interface is not intended for implementation by build script or plugin authors.</p>
 *
 * @since 4.3
 */
public interface RegularFileProperty extends FileSystemLocationProperty<RegularFile> {
    /**
     * {@inheritDoc}
     */
    @Override
    RegularFileProperty value(@Nullable RegularFile value);

    /**
     * {@inheritDoc}
     */
    @Override
    RegularFileProperty value(Provider<? extends RegularFile> provider);

    /**
     * {@inheritDoc}
     */
    @Override
    RegularFileProperty fileValue(@Nullable File file);

    /**
     * {@inheritDoc}
     */
    @Override
    RegularFileProperty fileProvider(Provider<File> provider);

    /**
     * {@inheritDoc}
     */
    @Override
    RegularFileProperty convention(RegularFile value);

    /**
     * {@inheritDoc}
     */
    @Override
    RegularFileProperty convention(Provider<? extends RegularFile> provider);
}