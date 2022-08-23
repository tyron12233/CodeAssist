package org.gradle.api.internal.file.collections;

import com.google.common.base.Objects;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.internal.state.ManagedFactory;

import javax.annotation.Nullable;

public class ManagedFactories {
    public static class ConfigurableFileCollectionManagedFactory implements ManagedFactory {
        private static final Class<?> PUBLIC_TYPE = ConfigurableFileCollection.class;
        public static final int FACTORY_ID = Objects.hashCode(PUBLIC_TYPE.getName());

        private final FileCollectionFactory fileCollectionFactory;

        public ConfigurableFileCollectionManagedFactory(FileCollectionFactory fileCollectionFactory) {
            this.fileCollectionFactory = fileCollectionFactory;
        }

        @Nullable
        @Override
        public <T> T fromState(Class<T> type, Object state) {
            if (!type.isAssignableFrom(PUBLIC_TYPE)) {
                return null;
            }
            // TODO - should retain display name
            return type.cast(fileCollectionFactory.configurableFiles().from(state));
        }

        @Override
        public int getId() {
            return FACTORY_ID;
        }
    }
}