package com.tyron.code;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.tyron.code.editor.CodeEditorFragment;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
            
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, CodeEditorFragment.newInstance())
                .commit();
    }
}
