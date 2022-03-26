package com.tyron.builder.api.tasks;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Task;

import java.util.Collection;

public interface TaskContainer extends Collection<Task> {

    TaskProvider<Task> register(String name, Action<? super Task> configurationAction);

    <T extends Task> TaskProvider<T> register(String name, Class<T> type, Action<? super T> configurationAction);

    <T extends Task> TaskProvider<T> register(String name, Class<T> type);
}
