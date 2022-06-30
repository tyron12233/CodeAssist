package com.tyron.builder.configuration.internal;

import com.tyron.builder.api.Action;
import com.tyron.builder.internal.DisplayName;

import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public class DefaultUserCodeApplicationContext implements UserCodeApplicationContext {

    private static final AtomicLong COUNTER = new AtomicLong();

    private final ThreadLocal<CurrentApplication> currentApplication = new ThreadLocal<>();

    @Override
    @Nullable
    public Application current() {
        return this.currentApplication.get();
    }

    @Override
    public void apply(DisplayName displayName, Action<? super UserCodeApplicationId> action) {
        CurrentApplication current = currentApplication.get();
        UserCodeApplicationId id = id();
        currentApplication.set(new CurrentApplication(id, displayName));
        try {
            action.execute(id);
        } finally {
            currentApplication.set(current);
        }
    }

    @Override
    public void gradleRuntime(Runnable runnable) {
        CurrentApplication current = currentApplication.get();
        currentApplication.set(null);
        try {
            runnable.run();
        } finally {
            currentApplication.set(current);
        }
    }

    @Override
    public <T> Action<T> reapplyCurrentLater(final Action<T> action) {
        final CurrentApplication current = currentApplication.get();
        if (current == null) {
            return action;
        }
        return current.reapplyLater(action);
    }

    private static UserCodeApplicationId id() {
        return new UserCodeApplicationId(COUNTER.incrementAndGet());
    }

    private class CurrentApplication implements Application {
        final UserCodeApplicationId id;
        final DisplayName displayName;

        public CurrentApplication(UserCodeApplicationId id, DisplayName displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        @Override
        public UserCodeApplicationId getId() {
            return id;
        }

        @Override
        public DisplayName getDisplayName() {
            return displayName;
        }

        @Override
        public void reapply(Runnable runnable) {
            CurrentApplication current = currentApplication.get();
            currentApplication.set(this);
            try {
                runnable.run();
            } finally {
                currentApplication.set(current);
            }
        }

        @Override
        public <T> T reapply(Supplier<T> action) {
            CurrentApplication current = currentApplication.get();
            currentApplication.set(this);
            try {
                return action.get();
            } finally {
                currentApplication.set(current);
            }
        }

        @Override
        public <T> Action<T> reapplyLater(Action<T> action) {
            return new Action<T>() {
                @Override
                public void execute(T t) {
                    CurrentApplication current = currentApplication.get();
                    currentApplication.set(CurrentApplication.this);
                    try {
                        action.execute(t);
                    } finally {
                        currentApplication.set(current);
                    }
                }
            };
        }
    }
}
