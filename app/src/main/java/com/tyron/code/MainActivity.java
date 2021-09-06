package com.tyron.code;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.tyron.code.ui.main.MainFragment;
import com.tyron.code.util.AndroidUtilities;
import com.tyron.resolver.DependencyResolver;
import com.tyron.resolver.DependencyUtils;
import com.tyron.resolver.ResolveTask;
import com.tyron.resolver.model.Dependency;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 0);

        if (getSupportFragmentManager().findFragmentByTag("main_fragment") == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, new MainFragment(), "main_fragment") //FileManagerFragment.newInstance(new File("/sdcard")))
                    .commit();
        }
    }
	
	@Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
    }
}
