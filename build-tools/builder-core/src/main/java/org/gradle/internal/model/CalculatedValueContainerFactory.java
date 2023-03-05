package org.gradle.internal.model;

import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.tasks.NodeExecutionContext;
import org.gradle.api.internal.tasks.TaskDependencyResolveContext;
import org.gradle.internal.DisplayName;
import org.gradle.internal.resources.ProjectLeaseRegistry;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;

import java.util.function.Supplier;

@ServiceScope(Scopes.BuildSession.class)
public class CalculatedValueContainerFactory {
    private final ProjectLeaseRegistry projectLeaseRegistry;
    private final NodeExecutionContext globalContext;

    public CalculatedValueContainerFactory(ProjectLeaseRegistry projectLeaseRegistry, ServiceRegistry buildScopeServices) {
        this.projectLeaseRegistry = projectLeaseRegistry;
        globalContext = buildScopeServices::get;
    }

    /**
     * Create a calculated value that may have dependencies or that may need to access mutable model state.
     */
    public <T, S extends ValueCalculator<? extends T>> CalculatedValueContainer<T, S> create(DisplayName displayName, S supplier) {
        return new CalculatedValueContainer<>(displayName, supplier, projectLeaseRegistry, globalContext);
    }

    /**
     * A convenience to create a calculated value that has no dependencies and that does not access any mutable model state.
     */
    public <T> CalculatedValueContainer<T, ?> create(DisplayName displayName, Supplier<? extends T> supplier) {
        return new CalculatedValueContainer<>(displayName, new SupplierBackedCalculator<>(supplier), projectLeaseRegistry, globalContext);
    }

    public <T, S extends ValueCalculator<? extends T>> CalculatedValueContainer<T, S> create(DisplayName displayName, T value) {
        return new CalculatedValueContainer<>(displayName, value);
    }

    private static class SupplierBackedCalculator<T> implements ValueCalculator<T> {
        private final Supplier<T> supplier;

        public SupplierBackedCalculator(Supplier<T> supplier) {
            this.supplier = supplier;
        }

        @Override
        public boolean usesMutableProjectState() {
            return false;
        }

        @Override
        public ProjectInternal getOwningProject() {
            return null;
        }

        @Override
        public void visitDependencies(TaskDependencyResolveContext context) {
        }

        @Override
        public T calculateValue(NodeExecutionContext context) {
            return supplier.get();
        }
    }
}
