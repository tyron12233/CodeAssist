package com.tyron.actions.impl;

import android.content.ComponentName;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;

import com.android.tools.r8.naming.T;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

public class AsyncMenu implements Menu {

    private final ExecutorService mMainExecutor;
    private final Menu mMenu;
    
    public AsyncMenu(ExecutorService executor, Menu menu) {
        mMainExecutor = executor;
        mMenu = menu;
    }

    private <T> T submit(Callable<T> tCallable) {
        try {
            return mMainExecutor.submit(tCallable).get();
        } catch (ExecutionException | InterruptedException e) {
            return null;
        }
    }
    
    @Override
    public MenuItem add(CharSequence title) {
        return submit(() -> mMenu.add(title));
    }

    @Override
    public MenuItem add(int titleRes) {
        return null;
    }

    @Override
    public MenuItem add(int groupId, int itemId, int order, CharSequence title) {
        return null;
    }

    @Override
    public MenuItem add(int groupId, int itemId, int order, int titleRes) {
        return null;
    }

    @Override
    public SubMenu addSubMenu(CharSequence title) {
        return submit(() -> mMenu.addSubMenu(title));
    }

    @Override
    public SubMenu addSubMenu(int titleRes) {
        return null;
    }

    @Override
    public SubMenu addSubMenu(int groupId, int itemId, int order, CharSequence title) {
        return null;
    }

    @Override
    public SubMenu addSubMenu(int groupId, int itemId, int order, int titleRes) {
        return null;
    }

    @Override
    public int addIntentOptions(int groupId, int itemId, int order, ComponentName caller,
                                Intent[] specifics, Intent intent, int flags,
                                MenuItem[] outSpecificItems) {
        return 0;
    }

    @Override
    public void removeItem(int id) {

    }

    @Override
    public void removeGroup(int groupId) {

    }

    @Override
    public void clear() {

    }

    @Override
    public void setGroupCheckable(int group, boolean checkable, boolean exclusive) {

    }

    @Override
    public void setGroupVisible(int group, boolean visible) {

    }

    @Override
    public void setGroupEnabled(int group, boolean enabled) {

    }

    @Override
    public boolean hasVisibleItems() {
        return false;
    }

    @Override
    public MenuItem findItem(int id) {
        return null;
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public MenuItem getItem(int index) {
        return null;
    }

    @Override
    public void close() {

    }

    @Override
    public boolean performShortcut(int keyCode, KeyEvent event, int flags) {
        return false;
    }

    @Override
    public boolean isShortcutKey(int keyCode, KeyEvent event) {
        return false;
    }

    @Override
    public boolean performIdentifierAction(int id, int flags) {
        return false;
    }

    @Override
    public void setQwertyMode(boolean isQwerty) {

    }
}
