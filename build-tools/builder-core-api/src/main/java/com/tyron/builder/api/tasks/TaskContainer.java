package com.tyron.builder.api.tasks;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.Task;

import java.util.Collection;
import java.util.Map;

import groovy.lang.Closure;

public interface TaskContainer extends Collection<Task> {

    TaskProvider<Task> register(String name, Action<? super Task> configurationAction);

    <T extends Task> TaskProvider<T> register(String name, Class<T> type, Action<? super T> configurationAction);

    <T extends Task> TaskProvider<T> register(String name, Class<T> type, Object... constructorArgs);

    <T extends Task> TaskProvider<T> register(String name, Class<T> type);

    <T extends Task> TaskProvider<T> register(String name);

    Task findByName(String name);

    Task getByPath(String path);
}