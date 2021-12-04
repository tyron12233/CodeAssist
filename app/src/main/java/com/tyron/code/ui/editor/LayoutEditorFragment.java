package com.tyron.code.ui.editor;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.flipkart.android.proteus.ProteusView;
import com.tyron.ProjectManager;
import com.tyron.builder.project.api.AndroidProject;
import com.tyron.builder.project.api.Project;
import com.tyron.code.ApplicationLoader;
import com.tyron.layoutpreview.BoundaryDrawingFrameLayout;
import com.tyron.layoutpreview.inflate.PreviewLayoutInflater;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LayoutEditorFragment extends Fragment implements ProjectManager.OnProjectOpenListener {

    /**
     * Creates a new LayoutEditorFragment instance for a layout xml file.
     * Make sure that the file exists and is a valid layout file and that
     * {@code ProjectManager#getCurrentProject} is not null
     */
    public static LayoutEditorFragment newInstance(File file) {
        Bundle args = new Bundle();
        args.putSerializable("file", file);
        LayoutEditorFragment fragment = new LayoutEditorFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private final ExecutorService mService = Executors.newSingleThreadExecutor();
    private File mCurrentFile;
    private FrameLayout mRoot;
    private boolean mWaitForProjectOpen;
    private PreviewLayoutInflater mInflater;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mCurrentFile = (File) requireArguments().getSerializable("file");
        if (savedInstanceState == null) {
            mInflater = new PreviewLayoutInflater(requireContext(),
                    (AndroidProject) ProjectManager.getInstance().getCurrentProject());
        }
        ProjectManager.getInstance().addOnProjectOpenListener(this);
        mWaitForProjectOpen = savedInstanceState != null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        ProjectManager.getInstance().removeOnProjectOpenListener(this);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mRoot = new BoundaryDrawingFrameLayout(requireContext());
        mRoot.setBackgroundColor(0xff121212);

        TextView test = new TextView(requireContext());
        test.setText("FRAGMENT");
        mRoot.addView(test);
        return mRoot;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            preview(mInflater);
        }
    }

    private void preview(PreviewLayoutInflater previewLayoutInflater) {
        previewLayoutInflater.parseResources().whenComplete((inflater, throwable) -> {
            if (throwable != null) {
                ApplicationLoader.showToast(throwable.getMessage());
            } else {
                ProteusView proteusView =
                        mInflater.inflateLayout(mCurrentFile.getName().replace(".xml", ""));
                addPreviewView(proteusView);
            }
        });
    }

    @MainThread
    private void addPreviewView(ProteusView view) {
        mRoot.addView(view.getAsView());
    }

    @Override
    public void onProjectOpen(Project project) {
        if (mWaitForProjectOpen) {
            mInflater = new PreviewLayoutInflater(requireActivity(), (AndroidProject) project);
            preview(mInflater);
        }
    }
}
