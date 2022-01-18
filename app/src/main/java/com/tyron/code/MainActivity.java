package com.tyron.code;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.tyron.code.ui.project.ProjectManagerFragment;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.java.JavaCompilerProvider;
import com.tyron.completion.java.JavaCompletionProvider;
import com.tyron.completion.main.CompletionEngine;
import com.tyron.completion.xml.providers.AndroidManifestCompletionProvider;
import com.tyron.completion.xml.providers.XmlCompletionProvider;
import com.tyron.completion.xml.XmlIndexProvider;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        StartupManager startupManager = new StartupManager();
        startupManager.addStartupActivity(() -> {
            CompletionEngine engine = CompletionEngine.getInstance();
            CompilerService index = CompilerService.getInstance();
            if (index.isEmpty()) {
                index.registerIndexProvider(JavaCompilerProvider.KEY, new JavaCompilerProvider());
                index.registerIndexProvider(XmlIndexProvider.KEY, new XmlIndexProvider());
                engine.registerCompletionProvider(new JavaCompletionProvider());
                engine.registerCompletionProvider(new XmlCompletionProvider());
                engine.registerCompletionProvider(new AndroidManifestCompletionProvider());
            }
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
