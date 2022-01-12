package com.tyron.code.ui.library;

import android.app.ProgressDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.transition.TransitionManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.transition.MaterialFade;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.api.Module;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.R;
import com.tyron.code.ui.library.adapter.LibraryManagerAdapter;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.code.util.DependencyUtils;
import com.tyron.resolver.DependencyResolver;
import com.tyron.resolver.model.Dependency;
import com.tyron.resolver.model.Pom;
import com.tyron.resolver.repository.PomRepository;
import com.tyron.resolver.repository.PomRepositoryImpl;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

public class LibraryManagerFragment extends Fragment implements ProjectManager.OnProjectOpenListener {

    public static final String TAG = LibraryManagerFragment.class.getSimpleName();
    private static final String ARG_PATH = "path";
    private static final Type TYPE = new TypeToken<List<Dependency>>(){}.getType();


    public static LibraryManagerFragment newInstance(String modulePath) {
        Bundle args = new Bundle();
        args.putString(ARG_PATH, modulePath);
        LibraryManagerFragment fragment = new LibraryManagerFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private PomRepository mPomRepository;
    private String mModulePath;
    private boolean isDumb = false;
    private LibraryManagerAdapter mAdapter;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        File cacheDir = ApplicationLoader.applicationContext.getExternalFilesDir("cache");
        mPomRepository = new PomRepositoryImpl();
        mPomRepository.setCacheDirectory(cacheDir);
        mPomRepository.addRepositoryUrl("https://repo1.maven.org/maven2");
        mPomRepository.addRepositoryUrl("https://maven.google.com");
        mPomRepository.addRepositoryUrl("https://jitpack.io");
        mPomRepository.addRepositoryUrl("https://jcenter.bintray.com");
        mPomRepository.initialize();
        mModulePath = requireArguments().getString(ARG_PATH);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.library_manager_fragment, container, false);

        mAdapter = new LibraryManagerAdapter();

        RecyclerView recyclerView = view.findViewById(R.id.libraries_recyclerview);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(mAdapter);

        Toolbar toolbar = view.findViewById(R.id.toolbar);
        toolbar.addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menu.add(R.string.menu_add_libs_gradle)
                        .setOnMenuItemClickListener(item -> {
                            Project currentProject =
                                    ProjectManager.getInstance().getCurrentProject();
                            if (currentProject != null) {
                                Module mainModule = currentProject.getMainModule();
                                File rootFile = mainModule.getRootFile();
                                File gradleFile = new File(rootFile, "build.gradle");
                                if (gradleFile.exists()) {
                                    try {
                                        List<Dependency> poms = DependencyUtils.parseGradle(mPomRepository,
                                                gradleFile, ILogger.EMPTY);
                                        List<Dependency> data = new ArrayList<>(mAdapter.getData());
                                        poms.forEach(dependency -> {
                                            if (!data.contains(dependency)) {
                                                data.add(dependency);
                                            }
                                        });
                                        mAdapter.submitList(data);
                                        if (!data.isEmpty()) {
                                            toggleEmptyView(false, false, "");
                                        }
                                        save(((JavaModule) mainModule).getLibraryFile(), data);
                                    } catch (IOException e) {
                                        new MaterialAlertDialogBuilder(requireContext())
                                                .setTitle(R.string.error)
                                                .setMessage(e.getMessage())
                                                .show();
                                    }
                                }
                            }
                            return true;
                        });
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                return false;
            }
        });

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Project project = ProjectManager.getInstance().getCurrentProject();
        if (project == null) {
            isDumb = true;
            ProjectManager.getInstance().addOnProjectOpenListener(this);
        } else {
            loadDependencies(project);
        }
        Toolbar toolbar = view.findViewById(R.id.toolbar);
        toolbar.setOnMenuItemClickListener(menu -> getParentFragmentManager().popBackStackImmediate());
    }

    @Override
    public void onProjectOpen(Project project) {
        if (isDumb) {
            requireActivity().runOnUiThread(() -> loadDependencies(project));
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        ProjectManager.getInstance().removeOnProjectOpenListener(this);
    }

    private void loadDependencies(Project project) {
        toggleEmptyView(true, true, "Parsing dependencies");

        List<Dependency> dependencies = new ArrayList<>();
        Module projectModule = project.getModule(new File(mModulePath));
        if (projectModule instanceof JavaModule) {
            File file = ((JavaModule) projectModule).getLibraryFile();
            try {
                if (!file.exists()) {
                    //noinspection ResultOfMethodCallIgnored ridiculous apis
                    file.createNewFile();
                }
            } catch (IOException e) {
                // ignored, just display an empty list
            }
            dependencies.addAll(parse(file));
        }

        mAdapter.submitList(dependencies);
        if (dependencies.isEmpty()) {
            toggleEmptyView(true, false, "No dependencies");
        } else {
            toggleEmptyView(false, false, "");
        }

        onPostLoad(projectModule);
    }

    private void onPostLoad(Module module) {
        if (!(module instanceof JavaModule)) {
            return;
        }
        JavaModule javaModule = ((JavaModule) module);

        FloatingActionButton fab = requireView().findViewById(R.id.fab_add_dependency);
        fab.setOnClickListener(v -> {
            FragmentManager fm = getChildFragmentManager();
            if (fm.findFragmentByTag(AddDependencyDialogFragment.TAG) == null) {
                AddDependencyDialogFragment fragment = new AddDependencyDialogFragment();
                fragment.show(fm, AddDependencyDialogFragment.TAG);
            }
        });
        getChildFragmentManager().setFragmentResultListener(AddDependencyDialogFragment.ADD_KEY,
                getViewLifecycleOwner(), (key, result) -> {
            String groupId = result.getString("groupId");
            String artifactId = result.getString("artifactId");
            String versionName = result.getString("versionName");
            Dependency dependency = new Dependency();
            dependency.setGroupId(groupId);
            dependency.setArtifactId(artifactId);
            dependency.setVersionName(versionName);
            dependency.setScope("compile");
            mAdapter.addDependency(dependency);
            save(javaModule.getLibraryFile(), mAdapter.getData());
            toggleEmptyView(false, false, "");
        });
        mAdapter.setItemLongClickListener((v, dependency) -> {
            v.setOnCreateContextMenuListener((menu, v1, menuInfo) -> {
                menu.add(R.string.dialog_delete).setOnMenuItemClickListener(item -> {
                    mAdapter.removeDependency(dependency);
                    save(javaModule.getLibraryFile(), mAdapter.getData());
                    if (mAdapter.getData().isEmpty()) {
                        toggleEmptyView(true, false, "No dependencies");
                    }
                    return true;
                });

                menu.add(R.string.menu_display_dependencies).setOnMenuItemClickListener(item -> {
                    DependencyResolver resolver = new DependencyResolver(mPomRepository);

                    ProgressDialog dialog = new ProgressDialog(requireContext());
                    dialog.show();

                    Executors.newSingleThreadExecutor().execute(() -> {
                        Pom pom = mPomRepository.getPom(dependency.toString());
                        if (pom != null) {
                            List<Pom> resolve = resolver.resolve(Collections.singletonList(pom));

                            if (getActivity() != null) {
                                requireActivity().runOnUiThread(() -> {
                                    dialog.dismiss();
                                    new MaterialAlertDialogBuilder(requireContext())
                                            .setTitle("Dependencies")
                                            .setMessage(resolve.stream().
                                                    map(Pom::toString)
                                                    .collect(Collectors.joining("\n")))
                                            .show();
                                });
                            }
                        }
                    });
                    return true;
                });
            });
            v.showContextMenu();
        });
    }

    private void toggleEmptyView(boolean show, boolean showProgress, String message) {
        View view = getView();
        if (view == null) {
            return;
        }

        View loadingLayout = view.findViewById(R.id.loading_layout_root);
        View recyclerView = view.findViewById(R.id.libraries_recyclerview);
        View progress = view.findViewById(R.id.progressbar);
        TextView textView = view.findViewById(R.id.empty_label);
        textView.setText(message);

        TransitionManager.beginDelayedTransition((ViewGroup) recyclerView.getParent(),
                new MaterialFade());
        if (show) {
            loadingLayout.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            loadingLayout.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
        progress.setVisibility(showProgress ? View.VISIBLE : View.GONE);
    }

    private List<Dependency> parse(File file) {
        if (file == null || !file.exists()) {
            return Collections.emptyList();
        }
        String contents;
        try {
            contents = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            contents = "";
        }
        if (contents.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return new Gson().fromJson(contents, TYPE);
        } catch (JsonSyntaxException e) {
            return Collections.emptyList();
        }
    }

    private Future<Boolean> save(File file, List<Dependency> dependencies) {
        String jsonString = new Gson().toJson(dependencies, TYPE);
        return CompletableFuture.supplyAsync(() -> {
            try {
                FileUtils.writeStringToFile(file, jsonString, StandardCharsets.UTF_8);
                return true;
            } catch (IOException e) {
                return false;
            }
        });
    }
}
