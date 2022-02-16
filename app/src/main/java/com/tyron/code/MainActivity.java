package com.tyron.code;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.tyron.code.ui.project.ProjectManagerFragment;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        if (getSupportFragmentManager().findFragmentByTag(ProjectManagerFragment.TAG) == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, new ProjectManagerFragment(),
                             ProjectManagerFragment.TAG)
                    .commit();
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
    }
}
