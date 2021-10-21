package com.tyron.code.ui.editor.log;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tyron.ProjectManager;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.log.LogViewModel;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.code.R;
import com.tyron.code.ui.editor.log.adapter.LogAdapter;
import com.tyron.code.ui.main.MainFragment;
import com.tyron.code.ui.main.MainViewModel;

import org.openjdk.javax.tools.Diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AppLogFragment extends Fragment {

    public static AppLogFragment newInstance(int id) {
        AppLogFragment fragment = new AppLogFragment();
        Bundle bundle = new Bundle();
        bundle.putInt("id", id);
        fragment.setArguments(bundle);
        return fragment;
    }

    private int id;
    private NestedScrollView mRoot;
    private LogAdapter mAdapter;
    private RecyclerView mRecyclerView;
    private TextView mLogView;

    private MainViewModel mMainViewModel;

    public AppLogFragment() {

    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        id = requireArguments().getInt("id");

        mMainViewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mRoot = new NestedScrollView(requireContext());
        mRoot.setFillViewport(true);

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
        mRoot.addView(mRecyclerView, new FrameLayout.LayoutParams(-1, -1));
        return mRoot;
    }

    private BroadcastReceiver mLogReceiver;

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        LogViewModel model = new ViewModelProvider(requireActivity())
                .get(LogViewModel.class);
        model.getLogs(id).observe(getViewLifecycleOwner(), this::process);

        if (id == LogViewModel.APP_LOG) {
            if (ProjectManager.getInstance().getCurrentProject() != null) {
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
                                model.d(LogViewModel.APP_LOG, wrapped);
                                break;
                            case "ERROR":
                                wrapped.setKind(Diagnostic.Kind.ERROR);
                                model.e(LogViewModel.APP_LOG, wrapped);
                                break;
                            case "WARNING":
                                wrapped.setKind(Diagnostic.Kind.WARNING);
                                model.w(LogViewModel.APP_LOG, wrapped);
                                break;
                        }
                    }
                };
                requireActivity().registerReceiver(mLogReceiver, new IntentFilter(ProjectManager.getInstance().getCurrentProject().getPackageName() + ".LOG"));
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

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

}
