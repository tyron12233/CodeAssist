package com.tyron.code.ui.settings.dynamic;

import android.os.Bundle;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.OnBackPressedDispatcher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleOwner;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.google.common.collect.Maps;
import com.tyron.builder.internal.service.DefaultServiceLocator;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.function.BiFunction;

public class DynamicSettingsFragment extends PreferenceFragmentCompat {

    private final Map<String, SettingsProvider> settingsProviders = Maps.newHashMap();

    private final Stack<String> screenStack = new Stack<>();

    private final OnBackPressedCallback onBackPressedCallback = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {

            if (!screenStack.isEmpty()) {
                screenStack.pop();
            }

            if (screenStack.isEmpty()) {
                requireActivity().finish();
            } else {
                setScreenInternal(screenStack.peek());
            }
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        OnBackPressedDispatcher dispatcher = requireActivity().getOnBackPressedDispatcher();
        dispatcher.addCallback((LifecycleOwner) this, onBackPressedCallback);
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        loadSettingsProviders();

        if (savedInstanceState != null) {
            screenStack.clear();

            //noinspection unchecked
            Stack<String> stack = (Stack<String>) savedInstanceState.getSerializable("STACK");
            screenStack.addAll(stack);

            setScreen(screenStack.pop());
        } else {
            setScreen("MAIN");
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putSerializable("STACK", screenStack);
    }

    private void loadSettingsProviders() {
        DefaultServiceLocator serviceLocator = new DefaultServiceLocator(getClass().getClassLoader());
        List<SettingsProvider> providers = serviceLocator.getAll(SettingsProvider.class);

        for (SettingsProvider provider : providers) {
            settingsProviders.put(provider.getScreenKey(), provider);
        }
    }

    public void setScreen(String screenKey) {
        settingsProviders.computeIfPresent(screenKey, (key, settingsProvider) -> {
            PreferenceScreen preferenceScreen = settingsProvider.createPreferenceScreen(this);
            if (preferenceScreen != null) {
                screenStack.push(screenKey);
                setPreferenceScreen(preferenceScreen);
            }
            return settingsProvider;
        });
    }

    /**
     * Sets the current screen without pushing it to the stack
     * @param screenKey The screen key
     */
    private void setScreenInternal(String screenKey) {
        settingsProviders.computeIfPresent(screenKey, (key, settingsProvider) -> {
            PreferenceScreen preferenceScreen = settingsProvider.createPreferenceScreen(this);
            if (preferenceScreen != null) {
                setPreferenceScreen(preferenceScreen);
            }
            return settingsProvider;
        });
    }

    @Override
    public void setPreferenceScreen(PreferenceScreen preferenceScreen) {
        super.setPreferenceScreen(preferenceScreen);

        requireActivity().setTitle(preferenceScreen.getTitle());
    }
}
