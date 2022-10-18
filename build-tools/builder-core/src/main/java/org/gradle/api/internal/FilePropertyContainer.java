package org.gradle.api.internal;

import com.google.common.collect.Lists;
import org.gradle.api.internal.tasks.TaskPropertyRegistration;
import org.gradle.api.tasks.TaskFilePropertyBuilder;

import java.util.Iterator;
import java.util.List;

/**
 * Container for {@link TaskPropertyRegistration}s that might not have a name. The container
 * ensures that whenever parameters are iterated they are always assigned a name.
 */
public class FilePropertyContainer<T extends TaskFilePropertyBuilder & TaskPropertyRegistration> implements Iterable<T> {
    private final List<T> properties = Lists.newArrayList();
    private boolean changed;
    private int unnamedPropertyCounter;

    private FilePropertyContainer() {
    }

    public static <T extends TaskFilePropertyBuilder & TaskPropertyRegistration> FilePropertyContainer<T> create() {
        return new FilePropertyContainer<T>();
    }

    public void add(T property) {
        properties.add(property);
        changed = true;
    }

    @Override
    public Iterator<T> iterator() {
        if (changed) {
            for (T propertySpec : properties) {
                String propertyName = propertySpec.getPropertyName();
                if (propertyName == null) {
                    propertyName = "$" + (++unnamedPropertyCounter);
                    propertySpec.withPropertyName(propertyName);
                }
            }
            changed = false;
        }
        return properties.iterator();
    }
}