package com.tyron.builder.project.impl;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.project.api.Module;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.util.concurrency.AtomicFieldUpdater;
import org.jetbrains.kotlin.com.intellij.util.keyFMap.KeyFMap;

import java.util.ArrayList;
import java.util.List;

public class ModuleImpl implements Module {

    @NotNull
    private volatile KeyFMap mDataMap = KeyFMap.EMPTY_MAP;
    private final List<Module> mDependingModules = new ArrayList<>();

    @Override
    public void addDependingModule(Module module) {
        mDependingModules.add(module);
    }

    @Override
    public List<Module> getDependingModules() {
        return ImmutableList.copyOf(mDependingModules);
    }

    @Override
    public <T> T getData(Key<T> key) {
        return mDataMap.get(key);
    }

    @Override
    public <T> void putData(Key<T> key, T value) {
        while (true) {
            KeyFMap map = mDataMap;
            KeyFMap newMap = value == null ? map.minus(key) : map.plus(key, value);
            if (newMap == map || updater.compareAndSet(this, map, newMap)) {
                break;
            }
        }
    }

    private static final AtomicFieldUpdater<ModuleImpl, KeyFMap> updater = AtomicFieldUpdater.forFieldOfType(ModuleImpl.class, KeyFMap.class);
}
