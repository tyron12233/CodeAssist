package com.tyron.builder.api.internal.file;

import com.google.common.base.Objects;
import com.tyron.builder.api.file.Directory;
import com.tyron.builder.api.file.DirectoryProperty;
import com.tyron.builder.api.file.RegularFile;
import com.tyron.builder.api.file.RegularFileProperty;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.state.ManagedFactory;
import com.tyron.builder.api.provider.Provider;

import java.io.File;

public class ManagedFactories {

    public static class RegularFileManagedFactory implements ManagedFactory {
        private static final Class<?> PUBLIC_TYPE = RegularFile.class;
        public static final int FACTORY_ID = Objects.hashCode(PUBLIC_TYPE.getName());
        private final FileFactory fileFactory;

        public RegularFileManagedFactory(FileFactory fileFactory) {
            this.fileFactory = fileFactory;
        }

        @Override
        public <T> T fromState(Class<T> type, Object state) {
            if (!type.isAssignableFrom(PUBLIC_TYPE)) {
                return null;
            }
            return type.cast(fileFactory.file((File) state));
        }

        @Override
        public int getId() {
            return FACTORY_ID;
        }
    }

    public static class RegularFilePropertyManagedFactory implements ManagedFactory {
        private static final Class<?> PUBLIC_TYPE = RegularFileProperty.class;
        public static final int FACTORY_ID = Objects.hashCode(PUBLIC_TYPE.getName());

        private final FilePropertyFactory filePropertyFactory;

        public RegularFilePropertyManagedFactory(FilePropertyFactory filePropertyFactory) {
            this.filePropertyFactory = filePropertyFactory;
        }

        @Override
        public <T> T fromState(Class<T> type, Object state) {
            if (!type.isAssignableFrom(PUBLIC_TYPE)) {
                return null;
            }
            return type.cast(filePropertyFactory.newFileProperty().value(Cast.<Provider<RegularFile>>uncheckedNonnullCast(state)));
        }

        @Override
        public int getId() {
            return FACTORY_ID;
        }
    }

    public static class DirectoryManagedFactory implements ManagedFactory {
        private static final Class<?> PUBLIC_TYPE = Directory.class;
        public static final int FACTORY_ID = Objects.hashCode(PUBLIC_TYPE.getName());

        private final FileFactory fileFactory;

        public DirectoryManagedFactory(FileFactory fileFactory) {
            this.fileFactory = fileFactory;
        }

        @Override
        public <T> T fromState(Class<T> type, Object state) {
            if (!type.isAssignableFrom(PUBLIC_TYPE)) {
                return null;
            }
            return type.cast(fileFactory.dir((File) state));
        }

        @Override
        public int getId() {
            return FACTORY_ID;
        }
    }

    public static class DirectoryPropertyManagedFactory implements ManagedFactory {
        private static final Class<?> PUBLIC_TYPE = DirectoryProperty.class;
        public static final int FACTORY_ID = Objects.hashCode(PUBLIC_TYPE.getName());

        private final FilePropertyFactory filePropertyFactory;

        public DirectoryPropertyManagedFactory(FilePropertyFactory filePropertyFactory) {
            this.filePropertyFactory = filePropertyFactory;
        }

        @Override
        public <T> T fromState(Class<T> type, Object state) {
            if (!type.isAssignableFrom(PUBLIC_TYPE)) {
                return null;
            }
            return type.cast(filePropertyFactory.newDirectoryProperty().value(Cast.<Provider<Directory>>uncheckedNonnullCast(state)));
        }

        @Override
        public int getId() {
            return FACTORY_ID;
        }
    }
}