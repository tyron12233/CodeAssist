package com.tyron.code;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.tyron.code.editor.CodeEditorFragment;
import androidx.core.content.ContextCompat;
import java.io.File;
import com.tyron.code.model.Project;
import com.tyron.code.parser.FileManager;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        //requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 2);
        
      //  Project project = new Project(new File("/sdcard/AppProjects/CodeAssist"));
      // FileManager.getInstance().openProject(project);™
		
        getSupportFragmentManager().beginTransaction()
            .replace(R.id.fragment_container, CodeEditorFragment.newInstance(new File(getFilesDir(), "Test.java")))
                .commit();
    }
}
