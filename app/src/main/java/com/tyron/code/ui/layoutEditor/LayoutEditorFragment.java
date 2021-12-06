package com.tyron.code.ui.layoutEditor;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.transition.TransitionManager;

import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.toolbox.Attributes;
import com.flipkart.android.proteus.value.Dimension;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.flipkart.android.proteus.value.Primitive;
import com.flipkart.android.proteus.value.Value;
import com.google.common.collect.ImmutableMap;
import com.tyron.ProjectManager;
import com.tyron.builder.project.api.AndroidProject;
import com.tyron.builder.project.api.Project;
import com.tyron.code.R;
import com.tyron.code.ui.layoutEditor.model.ViewPalette;
import com.tyron.completion.provider.CompletionEngine;
import com.tyron.layoutpreview.BoundaryDrawingFrameLayout;
import com.tyron.layoutpreview.inflate.PreviewLayoutInflater;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

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
    private LayoutEditorViewModel mEditorViewModel;

    private File mCurrentFile;
    private PreviewLayoutInflater mInflater;
    private BoundaryDrawingFrameLayout mEditorRoot;
    private EditorDragListener mDragListener;

    private LinearLayout mLoadingLayout;
    private TextView mLoadingText;

    private boolean isDumb;

    private View.OnLongClickListener mOnLongClickListener = v -> {
        View.DragShadowBuilder shadowBuilder = new View.DragShadowBuilder(v);
        ViewCompat.startDragAndDrop(v, null, shadowBuilder, v, 0);
        return true;
    };

    private View.OnClickListener mOnClickListener = v -> {
        Map<String, ViewTypeParser.AttributeSet.Attribute> parentAttributes = new HashMap<>();
        if (v.getParent() instanceof ProteusView) {
            parentAttributes.putAll(((ProteusView) v.getParent()).getViewManager().getLayoutParamsAttributes());
        }
        if (v instanceof ProteusView) {
            ProteusView view = (ProteusView) v;

            new AlertDialog.Builder(v.getContext())
                    .setTitle("Debug: View Attributes")
                    .setMessage(view.getViewManager().getLayout().attributes.stream()
                            .map(it -> view.getViewManager().getAttributeName(it.id) + ": " + it.value)
                            .collect(Collectors.joining("\n")) + "\n" +
                            view.getViewManager().getLayout().extras.toString())
                    .setNeutralButton("Show available attrs", (d, which) -> {
                        new AlertDialog.Builder(v.getContext())
                                .setTitle("Available attributes")
                                .setMessage(String.join("\n",
                                        view.getViewManager().getAvailableAttributes().keySet()) +
                                        "\n parent:" +
                                        String.join("\n", parentAttributes.keySet()))
                                .show();
                    })
                    .show();
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mCurrentFile = (File) requireArguments().getSerializable("file");
        isDumb = ProjectManager.getInstance().getCurrentProject() == null ||
                CompletionEngine.isIndexing();
        mEditorViewModel = new ViewModelProvider(this).get(LayoutEditorViewModel.class);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.layout_editor_fragment, container, false);

        mLoadingLayout = root.findViewById(R.id.loading_root);
        mLoadingText = root.findViewById(R.id.loading_text);

        mEditorRoot = root.findViewById(R.id.editor_root);
        mDragListener = new EditorDragListener(mEditorRoot);
        mDragListener.setInflateCallback((parent, palette) -> {
            Layout layout = new Layout(palette.getClassName());
            ProteusView inflated = mInflater.getContext()
                    .getInflater()
                    .inflate(layout, new ObjectValue(), parent, 0);
            palette.getDefaultValues().forEach((key, value) -> {
                inflated.getViewManager().updateAttribute(key, value);
            });
            return inflated;
        });
        mDragListener.setDelegate(new EditorDragListener.Delegate() {
            @Override
            public void onAddView(ViewGroup parent, View view) {
                if (view instanceof ViewGroup) {
                    setDragListeners(((ViewGroup) view));
                }
                setClickListeners(view);
            }

            @Override
            public void onRemoveView(ViewGroup parent, View view) {

            }
        });
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mEditorViewModel.setPalettes(populatePalettes());
        if (isDumb) {
            setLoadingText("Indexing");
        } else {
            createInflater();
        }
    }

    private void createInflater() {
        mInflater = new PreviewLayoutInflater(requireContext(),
                (AndroidProject) ProjectManager.getInstance().getCurrentProject());
        setLoadingText("Parsing xml files");
        mInflater.parseResources(mService).whenComplete((inflater, exception) -> {
            requireActivity().runOnUiThread(() -> {
                afterParse(inflater);
            });
        });
    }

    private void afterParse(PreviewLayoutInflater inflater) {
        mInflater = inflater;
        setLoadingText("Inflating xml");
        inflateFile(mCurrentFile);
    }

    private void inflateFile(File file) {
        ProteusView inflatedView = mInflater.inflateLayout(file.getName()
                .replace(".xml", ""));
        setLoadingText(null);

        mEditorRoot.removeAllViews();
        mEditorRoot.addView(inflatedView.getAsView());
        setDragListeners(mEditorRoot);
        setClickListeners(mEditorRoot);
    }

    private void setDragListeners(ViewGroup viewGroup) {
        viewGroup.setOnDragListener(mDragListener);
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child instanceof ViewGroup) {
                setDragListeners(((ViewGroup) child));
            }
        }
    }

    private void setClickListeners(View view) {
        view.setOnLongClickListener(mOnLongClickListener);
        view.setOnClickListener(mOnClickListener);
        if (view instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view;
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                setClickListeners(child);
            }
        }
    }


    /**
     * Show a loading text to the editor
     *
     * @param message The message to be displayed, pass null to remove the loading text
     */
    private void setLoadingText(@Nullable String message) {
        TransitionManager.beginDelayedTransition((ViewGroup) mLoadingLayout.getParent());
        if (null == message) {
            mLoadingLayout.setVisibility(View.GONE);
        } else {
            mLoadingLayout.setVisibility(View.VISIBLE);
            mLoadingText.setText(message);
        }
    }

    private List<ViewPalette> populatePalettes() {
        List<ViewPalette> palettes = new ArrayList<>();
        palettes.add(createPalette("android.widget.LinearLayout", R.drawable.crash_ic_close));
        palettes.add(createPalette("android.widget.TextView",
                R.drawable.crash_ic_bug_report,
                ImmutableMap.of(Attributes.TextView.Text, new Primitive("TextView"))));
        return palettes;
    }

    private ViewPalette createPalette(@NonNull String className, @DrawableRes int icon) {
        return createPalette(className, icon, Collections.emptyMap());
    }

    private ViewPalette createPalette(@NonNull String className,
                                      @DrawableRes int icon,
                                      Map<String, Value> attributes) {
        String name = className.substring(className.lastIndexOf('.') + 1);
        ViewPalette.Builder builder = ViewPalette.builder()
                .setClassName(className)
                .setName(name)
                .setIcon(icon)
                .addDefaultValue(Attributes.View.MinHeight, Dimension.valueOf("25dp"))
                .addDefaultValue(Attributes.View.MinWidth, Dimension.valueOf("50dp"));

        attributes.forEach(builder::addDefaultValue);
        return builder.build();
    }

    @Override
    public void onProjectOpen(Project project) {
        if (isDumb) {
            createInflater();
        }
    }
}
