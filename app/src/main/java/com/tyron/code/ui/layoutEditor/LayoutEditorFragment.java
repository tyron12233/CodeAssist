package com.tyron.code.ui.layoutEditor;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.LayoutTransition;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.transition.TransitionManager;

import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.toolbox.Attributes;
import com.flipkart.android.proteus.toolbox.ProteusHelper;
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
import com.tyron.code.ui.layoutEditor.attributeEditor.AttributeEditorDialogFragment;
import com.tyron.code.ui.layoutEditor.model.ViewPalette;
import com.tyron.completion.provider.CompletionEngine;
import com.tyron.layoutpreview.BoundaryDrawingFrameLayout;
import com.tyron.layoutpreview.inflate.PreviewLayoutInflater;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import kotlin.Pair;

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

    private final View.OnLongClickListener mOnLongClickListener = v -> {
        View.DragShadowBuilder shadowBuilder = new View.DragShadowBuilder(v);
        ViewCompat.startDragAndDrop(v, null, shadowBuilder, v, 0);
        return true;
    };

    private final View.OnClickListener mOnClickListener = v -> {
//        Map<String, ViewTypeParser.AttributeSet.Attribute> parentAttributes = new HashMap<>();
//        if (v.getParent() instanceof ProteusView) {
//            parentAttributes.putAll(((ProteusView) v.getParent()).getViewManager().getLayoutParamsAttributes());
//        }
        if (v instanceof ProteusView) {
            ProteusView view = (ProteusView) v;

            ArrayList<Pair<String, String>> attributes = new ArrayList<>();
            for (Layout.Attribute attribute :
                    view.getViewManager().getLayout().getAttributes()) {
                String name = view.getViewManager().getAttributeName(attribute.id);
                attributes.add(new Pair<>(name, attribute.value.toString()));
            }
            AttributeEditorDialogFragment.newInstance(attributes)
                    .show(getChildFragmentManager(), null);

            getChildFragmentManager().setFragmentResultListener(
                    AttributeEditorDialogFragment.KEY_ATTRIBUTE_CHANGED,
                    getViewLifecycleOwner(),
                    (requestKey, result) ->
                            view.getViewManager().updateAttribute(result.getString("key"),
                                    result.getString("value")));
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mCurrentFile = (File) requireArguments().getSerializable("file");
        isDumb = ProjectManager.getInstance().getCurrentProject() == null ||
                CompletionEngine.isIndexing();
        mEditorViewModel = new ViewModelProvider(this)
                .get(LayoutEditorViewModel.class);
        requireActivity().getOnBackPressedDispatcher().addCallback(new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                getParentFragmentManager().popBackStack();
            }
        });
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
            palette.getDefaultValues().forEach((key, value) ->
                    inflated.getViewManager().updateAttribute(key, value.toString()));
            return inflated;
        });
        mDragListener.setDelegate(new EditorDragListener.Delegate() {
            @Override
            public void onAddView(ViewGroup parent, View view) {
                if (view instanceof ViewGroup) {
                    setDragListeners(((ViewGroup) view));
                }
                setClickListeners(view);
                mEditorRoot.postDelayed(() -> mEditorRoot.requestLayout(), 100);

                if (parent instanceof ProteusView && view instanceof ProteusView) {
                    ProteusView proteusParent = (ProteusView) parent;
                    ProteusView proteusChild = (ProteusView) view;
                    ProteusHelper.addChildToLayout(proteusParent, proteusChild);
                }
            }

            @Override
            public void onRemoveView(ViewGroup parent, View view) {
                if (parent instanceof ProteusView && view instanceof ProteusView) {
                    ProteusView proteusParent = (ProteusView) parent;
                    ProteusView proteusChild = (ProteusView) view;
                    ProteusHelper.removeChildFromLayout(proteusParent, proteusChild);
                }
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
        mInflater.parseResources(mService).whenComplete((inflater, exception) ->
                requireActivity().runOnUiThread(() ->
                        afterParse(inflater)));
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

        try {
            LayoutTransition transition = new LayoutTransition();
            transition.addTransitionListener(new LayoutTransition.TransitionListener() {
                @Override
                public void startTransition(LayoutTransition transition, ViewGroup container, View view, int transitionType) {
                    transition.getAnimator(transitionType).addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            mEditorRoot.postDelayed(() -> mEditorRoot.invalidate(), 70);
                        }
                    });
                }

                @Override
                public void endTransition(LayoutTransition transition, ViewGroup container, View view, int transitionType) {

                }
            });
            viewGroup.setLayoutTransition(new LayoutTransition());
        } catch (Throwable e) {
            // ignored, some ViewGroup's may not allow layout transitions
        }
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
        palettes.add(createPalette("android.widget.FrameLayout", R.drawable.ic_baseline_add_24));
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
