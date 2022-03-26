package com.tyron.builder.api.internal.project;

import com.tyron.builder.api.internal.reflect.validation.TypeValidationContext;
import com.tyron.builder.api.internal.resources.ResourceLock;
import com.tyron.builder.api.internal.service.DefaultServiceRegistry;
import com.tyron.builder.api.internal.service.ServiceRegistry;
import com.tyron.builder.api.internal.tasks.DefaultTaskContainer;
import com.tyron.builder.api.internal.tasks.TaskContainerInternal;
import com.tyron.builder.api.internal.tasks.properties.PropertyVisitor;
import com.tyron.builder.api.internal.tasks.properties.PropertyWalker;

import java.util.concurrent.locks.ReentrantLock;

public abstract class AbstractProject implements ProjectInternal {

    private final ResourceLock tempLock = new ResourceLock() {

        private final ReentrantLock lock = new ReentrantLock();

        @Override
        public boolean isLocked() {
            return lock.isLocked();
        }

        @Override
        public boolean isLockedByCurrentThread() {
            return lock.isHeldByCurrentThread();
        }

        @Override
        public boolean tryLock() {
            return lock.tryLock();
        }

        @Override
        public void unlock() {
            lock.unlock();
        }

        @Override
        public String getDisplayName() {
            return lock.toString();
        }
    };
    private final DefaultServiceRegistry serviceRegistry;
    private final TaskContainerInternal taskContainer;

    public AbstractProject() {
        serviceRegistry = new DefaultServiceRegistry();
        serviceRegistry.addService(PropertyWalker.class, new PropertyWalker() {
            @Override
            public void visitProperties(Object instance,
                                        TypeValidationContext validationContext,
                                        PropertyVisitor visitor) {

            }
        });
        taskContainer = new DefaultTaskContainer(this);
    }

    @Override
    public ProjectState getOwner() {
        return () -> tempLock;
    }

    @Override
    public ServiceRegistry getServices() {
        return serviceRegistry;
    }

    @Override
    public TaskContainerInternal getTaskContainer() {
        return taskContainer;
    }
}
