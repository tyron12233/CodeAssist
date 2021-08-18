package com.tyron.code;

import android.app.*;
import android.os.*;
import android.widget.Toast;
import android.widget.EditText;
import android.widget.Button;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import android.view.View.OnClickListener;
import android.view.View;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Arrays;
import com.tyron.code.parser.JavaParser;
import com.tyron.code.completion.FindCompletionsAt;
import com.sun.tools.javac.tree.JCTree;
import com.sun.source.util.TreePathScanner;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.tyron.code.completion.CompletionProvider;
import com.tyron.code.model.CompletionItem;
import android.text.Layout;
import com.tyron.code.model.CompletionList;
import com.tyron.code.editor.CodeEditorFragment;
import androidx.appcompat.app.AppCompatActivity;
import com.tyron.code.util.NoExitSecurityManager;

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
