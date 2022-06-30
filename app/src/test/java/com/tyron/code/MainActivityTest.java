package com.tyron.code;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;

import com.tyron.code.ui.project.ProjectManagerFragment;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
public class MainActivityTest {

    @Test
    public void testShouldShowProjectManagerFragment() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                final FragmentManager fm = activity.getSupportFragmentManager();
                final Fragment fragmentByTag = fm.findFragmentByTag(ProjectManagerFragment.TAG);
                assert fragmentByTag != null;
            });
        }

    }
}
