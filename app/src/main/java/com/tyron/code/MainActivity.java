package com.tyron.code;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.tyron.code.ui.main.MainFragment;
import com.tyron.code.ui.project.ProjectManagerFragment;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
       //x requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 0);

        if (getSupportFragmentManager().findFragmentByTag(ProjectManagerFragment.TAG) == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, new ProjectManagerFragment(), ProjectManagerFragment.TAG)
                    .commit();
        }
    }
	
	@Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
    }
}
