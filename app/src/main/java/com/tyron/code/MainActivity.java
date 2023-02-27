package com.tyron.code;

import android.os.Bundle;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.fragment.app.FragmentContainerView;

import com.tyron.code.ui.project.ProjectManagerFragment;
import com.tyron.code.util.UiUtilsKt;

import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.persistent.FSRecords;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.CoreFileBasedIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.CoreStubIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileIdStorage;
import org.jetbrains.kotlin.com.intellij.util.indexing.StorageException;

import java.io.IOException;

import kotlin.Unit;
import kotlin.jvm.functions.Function4;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        FragmentContainerView containerView = findViewById(R.id.fragment_container);
        UiUtilsKt.applySystemWindowInsets(containerView, false, (left, top, right, bottom) -> {
            containerView.setPadding(left, 0, right, 0);
            return Unit.INSTANCE;
        });

        if (getSupportFragmentManager().findFragmentByTag(ProjectManagerFragment.TAG) == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container,
                            new ProjectManagerFragment(),
                            ProjectManagerFragment.TAG)
                    .commit();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            ((CoreStubIndex) StubIndex.getInstance()).flush();
            ((CoreFileBasedIndex) FileBasedIndex.getInstance()).flush();
            FileIdStorage.saveIds();
            FSRecords.flush();
        } catch (IOException | StorageException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
    }

    @Override
    public boolean onKeyShortcut(int keyCode, KeyEvent event) {
        return super.onKeyShortcut(keyCode, event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return super.onKeyUp(keyCode, event);
    }
}
