package com.tyron.code.ui.editor.log;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tyron.builder.log.LogViewModel;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.project.Project;
import com.tyron.code.ui.editor.log.adapter.LogAdapter;
import com.tyron.code.ui.main.MainViewModel;
import com.tyron.code.ui.project.ProjectManager;

import java.util.List;

public class AppLogFragment extends Fragment
        implements ProjectManager.OnProjectOpenListener {

    public static AppLogFragment newInstance(int id) {
        AppLogFragment fragment = new AppLogFragment();
        Bundle bundle = new Bundle();
        bundle.putInt("id", id);
        fragment.setArguments(bundle);
        return fragment;
    }

    private int id;
    private MainViewModel mMainViewModel;
    private LogViewModel mModel;
    private LogAdapter mAdapter;
    private RecyclerView mRecyclerView;

    public AppLogFragment() {

    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        id = requireArguments().getInt("id");

        mModel = new ViewModelProvider(requireActivity()).get(LogViewModel.class);
        mMainViewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        FrameLayout mRoot = new FrameLayout(requireContext());

        mAdapter = new LogAdapter();
        mAdapter.setListener(diagnostic -> {
            if (diagnostic.getSource() != null) {
                mMainViewModel.openFile(diagnostic.getSource());
            }
        });
        mRecyclerView = new RecyclerView(requireContext());
        mRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        mRecyclerView.setAdapter(mAdapter);
        mRoot.addView(mRecyclerView,
                new FrameLayout.LayoutParams(-1, -1));
        return mRoot;
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mModel.getLogs(id).observe(getViewLifecycleOwner(), this::process);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }

    private void process(List<DiagnosticWrapper> texts) {
        mAdapter.submitList(texts);

        if (mRecyclerView.canScrollVertically(-1)) {
            mRecyclerView.scrollToPosition(mAdapter.getItemCount());
        }
    }

    @Override
    public void onProjectOpen(Project project) {

    }
}
