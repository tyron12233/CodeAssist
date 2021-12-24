package com.tyron.code;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.tyron.code.ui.project.ProjectManagerFragment;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.java.JavaCompilerProvider;
import com.tyron.completion.java.JavaCompletionProvider;
import com.tyron.completion.main.CompletionEngine;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        StartupManager startupManager = new StartupManager();
        startupManager.addStartupActivity(() -> {
            CompilerService.getInstance().clear();
            CompletionEngine.getInstance().clear();
            CompilerService.getInstance().registerIndexProvider(JavaCompilerProvider.KEY, new JavaCompilerProvider());
            CompletionEngine.getInstance().registerCompletionProvider(new JavaCompletionProvider());
        });
        startupManager.startup();

        if (getSupportFragmentManager().findFragmentByTag(ProjectManagerFragment.TAG) == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container,
                            new ProjectManagerFragment(),
                            ProjectManagerFragment.TAG)
                    .commit();
        }
    }
	
	@Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
    }
}
