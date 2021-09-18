package com.tyron.code;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tyron.PsiTest;
import com.tyron.builder.parser.FileManager;
import com.tyron.code.ui.main.MainFragment;
import com.tyron.code.util.AndroidUtilities;
import com.tyron.layoutpreview.PreviewContext;
import com.tyron.layoutpreview.PreviewLayoutInflater;
import com.tyron.layoutpreview.PreviewLoader;
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
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    PsiTest test = new PsiTest();
                }, 2000);

//        PreviewLoader loader = new PreviewLoader(this);
//        findViewById(android.R.id.content)
//                .postDelayed(() -> {
//                    loader.addAssetPath(FileManager.getInstance().getCurrentProject().getBuildDirectory() + "/bin/signed.apk");
//                    PreviewContext context = loader.getPreviewContext();
//                    context.setTheme(context.getResources().getIdentifier("Theme_MyApplication", "style", "com.tyron.preview"));
//                    context.setTheme(getTheme());
//                    int main = context.getResources().getIdentifier("activity_main", "layout", "com.tyron.preview");
//
//                    View view = new PreviewLayoutInflater(context).inflate(main, null, false);
//                    new MaterialAlertDialogBuilder(this)
//                            .setView(view)
//                            .show();
//                }, 700);
    }
	
	@Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
    }
}
