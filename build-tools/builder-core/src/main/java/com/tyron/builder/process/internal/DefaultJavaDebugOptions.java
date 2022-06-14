package com.tyron.builder.process.internal;

import com.tyron.builder.api.internal.model.InstantiatorBackedObjectFactory;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.provider.Property;
import com.tyron.builder.internal.reflect.DirectInstantiator;
import com.tyron.builder.process.JavaDebugOptions;

import javax.inject.Inject;
import java.util.Objects;

public class DefaultJavaDebugOptions implements JavaDebugOptions {
    private final Property<Boolean> enabled;
    private final Property<Integer> port;
    private final Property<Boolean> server;
    private final Property<Boolean> suspend;

    @Inject
    public DefaultJavaDebugOptions(ObjectFactory objectFactory) {
        this.enabled = objectFactory.property(Boolean.class).convention(false);
        this.port = objectFactory.property(Integer.class).convention(5005);
        this.server = objectFactory.property(Boolean.class).convention(true);
        this.suspend = objectFactory.property(Boolean.class).convention(true);
    }

    public DefaultJavaDebugOptions() {
        // Ugly, but there are a few places where we need to instantiate a JavaDebugOptions and a regular ObjectFactory service
        // is not available.
        this(new InstantiatorBackedObjectFactory(DirectInstantiator.INSTANCE));
    }

    @Override
    public int hashCode() {
        return Objects.hash(getEnabled().get(), getPort().get(), getServer().get(), getSuspend().get());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DefaultJavaDebugOptions that = (DefaultJavaDebugOptions) o;
        return enabled.get() == that.enabled.get()
                && port.get().equals(that.port.get())
                && server.get()  == that.server.get()
                && suspend.get()  == that.suspend.get();
    }

    @Override
    public Property<Boolean> getEnabled() {
        return enabled;
    }

    @Override
    public Property<Integer> getPort() {
        return port;
    }

    @Override
    public Property<Boolean> getServer() {
        return server;
    }

    @Override
    public Property<Boolean> getSuspend() {
        return suspend;
    }
}
