package com.tyron.code.ui.project;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.transition.TransitionManager;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.transition.MaterialFade;
import com.google.android.material.transition.MaterialSharedAxis;
import com.tyron.builder.model.Project;
import com.tyron.code.R;
import com.tyron.code.ui.main.MainFragment;
import com.tyron.code.ui.project.adapter.ProjectManagerAdapter;
import com.tyron.code.ui.wizard.WizardFragment;
import com.tyron.common.SharedPreferenceKeys;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class ProjectManagerFragment extends Fragment {

    public static final String TAG = ProjectManagerFragment.class.getSimpleName();

    private SharedPreferences mPreferences;
    private RecyclerView mRecyclerView;
    private ProjectManagerAdapter mAdapter;
    private FloatingActionButton mCreateProjectFab;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setExitTransition(new MaterialSharedAxis(MaterialSharedAxis.X, false));
        mPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext());
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {

        NestedScrollView scrollView = view.findViewById(R.id.scrolling_view);
        scrollView.setNestedScrollingEnabled(false);

        MaterialToolbar toolbar = view.findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.app_name);

        mCreateProjectFab = view.findViewById(R.id.create_project_fab);
        mCreateProjectFab.setOnClickListener(v -> {
            WizardFragment wizardFragment = new WizardFragment();
            wizardFragment.setOnProjectCreatedListener(this::openProject);
            getParentFragmentManager().beginTransaction()
                    .add(R.id.fragment_container, wizardFragment)
                    .addToBackStack(null)
                    .commit();
        });
        mAdapter = new ProjectManagerAdapter();
        mAdapter.setOnProjectSelectedListener(this::openProject);
        mRecyclerView = view.findViewById(R.id.projects_recycler);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        mRecyclerView.setAdapter(mAdapter);

        loadProjects();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.project_manager_fragment, container, false);
    }

    @Override
    public void onResume() {
        super.onResume();

        loadProjects();
    }

    private void openProject(Project project) {
        MainFragment fragment = MainFragment.newInstance(project.mRoot.getAbsolutePath());
        getParentFragmentManager().beginTransaction()
                .add(R.id.fragment_container, fragment)
                .addToBackStack(null)
                .commit();
    }

    private void loadProjects() {
        toggleLoading(true);

        Executors.newSingleThreadExecutor().execute(() -> {
            String path = mPreferences.getString(SharedPreferenceKeys.PROJECT_SAVE_PATH,
                    requireContext().getExternalFilesDir("Projects").getAbsolutePath());
            File projectDir = new File(path);
            File[] directories = projectDir.listFiles(File::isDirectory);

            List<Project> projects = new ArrayList<>();
            if (directories != null) {
                for (File directory : directories) {
                    Project project = new Project(directory);
                    if (project.isValidProject()) {
                        projects.add(project);
                    }
                }
            }

            if (getActivity() != null) {
                requireActivity().runOnUiThread(() -> {
                    toggleLoading(false);
                    mAdapter.submitList(projects);
                });
            }
        });
    }

    private void toggleLoading(boolean show) {
        if (getActivity() == null || isDetached()) {
            return;
        }

        View recycler = requireView().findViewById(R.id.projects_recycler);
        View empty = requireView().findViewById(R.id.empty_container);

        TransitionManager.beginDelayedTransition((ViewGroup) recycler.getParent(), new MaterialFade());
        if (show) {
            recycler.setVisibility(View.GONE);
            empty.setVisibility(View.VISIBLE);
        } else {
            recycler.setVisibility(View.VISIBLE);
            empty.setVisibility(View.GONE);
        }
    }
}
