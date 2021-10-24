package com.tyron.code.ui.editor.log;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tyron.ProjectManager;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.log.LogViewModel;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.model.Project;
import com.tyron.code.ui.editor.log.adapter.LogAdapter;
import com.tyron.code.ui.main.MainFragment;

import org.openjdk.javax.tools.Diagnostic;

import java.util.List;

public class AppLogFragment extends Fragment implements ProjectManager.OnProjectOpenListener {

    public static AppLogFragment newInstance(int id) {
        AppLogFragment fragment = new AppLogFragment();
        Bundle bundle = new Bundle();
        bundle.putInt("id", id);
        fragment.setArguments(bundle);
        return fragment;
    }

    private int id;
    private LogViewModel mModel;
    private LogAdapter mAdapter;
    private RecyclerView mRecyclerView;

    public AppLogFragment() {

    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        id = requireArguments().getInt("id");
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        NestedScrollView mRoot = new NestedScrollView(requireContext());

        mAdapter = new LogAdapter();
        mAdapter.setListener(diagnostic -> {
            // MainFragment -> BottomEditorFragment -> AppLogFragment
            Fragment parent = getParentFragment();
            if (parent != null) {
                Fragment main = parent.getParentFragment();
                if (main instanceof MainFragment) {
                    ((MainFragment) main).openFile(diagnostic.getSource(),
                            (int) diagnostic.getLineNumber() - 1, 0);
                }
            }
        });
        mRecyclerView = new RecyclerView(requireContext());
        mRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        mRecyclerView.setAdapter(mAdapter);
        mRoot.addView(mRecyclerView,
                new FrameLayout.LayoutParams(-1, -2));
        return mRoot;
    }

    private BroadcastReceiver mLogReceiver;

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mModel = new ViewModelProvider(requireActivity())
                .get(LogViewModel.class);
        mModel.getLogs(id).observe(getViewLifecycleOwner(), this::process);

        if (id == LogViewModel.APP_LOG) {
            ProjectManager.getInstance().addOnProjectOpenListener(this);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        ProjectManager.getInstance().removeOnProjectOpenListener(this);
        if (mLogReceiver != null) {
            requireActivity().unregisterReceiver(mLogReceiver);
        }
    }

    private void process(List<DiagnosticWrapper> texts) {
        mAdapter.submitList(texts);

        if (mRecyclerView.canScrollVertically(-1)) {
            mRecyclerView.scrollToPosition(mAdapter.getItemCount());
        }
    }

    @Override
    public void onProjectOpen(Project project) {
        if (mLogReceiver != null) {
            requireActivity().unregisterReceiver(mLogReceiver);
        }

        mLogReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String type = intent.getExtras().getString("type", "DEBUG");
                String message = intent.getExtras().getString("message", "No message provided");
                DiagnosticWrapper wrapped = ILogger.wrap(message);

                switch (type) {
                    case "DEBUG":
                    case "INFO":
                        wrapped.setKind(Diagnostic.Kind.NOTE);
                        mModel.d(LogViewModel.APP_LOG, wrapped);
                        break;
                    case "ERROR":
                        wrapped.setKind(Diagnostic.Kind.ERROR);
                        mModel.e(LogViewModel.APP_LOG, wrapped);
                        break;
                    case "WARNING":
                        wrapped.setKind(Diagnostic.Kind.WARNING);
                        mModel.w(LogViewModel.APP_LOG, wrapped);
                        break;
                }
            }
        };
        requireActivity().registerReceiver(mLogReceiver, new IntentFilter(ProjectManager.getInstance().getCurrentProject().getPackageName() + ".LOG"));
    }
}
