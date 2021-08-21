package com.tyron.code;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.tyron.code.editor.CodeEditorFragment;
import androidx.core.content.ContextCompat;
import java.io.File;
import com.tyron.code.model.Project;
import com.tyron.code.parser.FileManager;
import com.tyron.code.main.MainFragment;
import androidx.appcompat.widget.Toolbar;
import com.tyron.code.file.FileManagerFragment;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 0);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        
        getSupportFragmentManager().beginTransaction()
            .replace(R.id.fragment_container, FileManagerFragment.newInstance(new File("/sdcard")))
                .commit();
    }
}
