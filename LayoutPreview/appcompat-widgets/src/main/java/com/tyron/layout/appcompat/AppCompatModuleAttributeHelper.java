package com.tyron.layout.appcompat;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.flipkart.android.proteus.ProteusBuilder;
import com.flipkart.android.proteus.ProteusConstants;
import com.flipkart.android.proteus.parser.ParseHelper;
import com.flipkart.android.proteus.processor.AttributeProcessor;
import com.flipkart.android.proteus.processor.GravityAttributeProcessor;
import com.flipkart.android.proteus.processor.StringAttributeProcessor;
import com.flipkart.android.proteus.toolbox.Attributes;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class AppCompatModuleAttributeHelper {


    public static <V extends View> void register(ProteusBuilder builder) {

        Map<String, AttributeProcessor> processors = new LinkedHashMap<>(4);

        processors.put(Attributes.View.LayoutGravity, new GravityAttributeProcessor<V>() {
            @Override
            public void setGravity(V view, @Gravity int gravity) {
                ViewGroup.LayoutParams layoutParams = view.getLayoutParams();

                if (layoutParams instanceof LinearLayout.LayoutParams) {
                    LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) layoutParams;
                    params.gravity = gravity;
                    view.setLayoutParams(layoutParams);
                } else if (layoutParams instanceof FrameLayout.LayoutParams) {
                    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) layoutParams;
                    params.gravity = gravity;
                    view.setLayoutParams(layoutParams);
                } else if (layoutParams instanceof CoordinatorLayout.LayoutParams) {
                    CoordinatorLayout.LayoutParams params = (CoordinatorLayout.LayoutParams) layoutParams;
                    params.gravity = gravity;
                    view.setLayoutParams(layoutParams);
                }
            }
        });

        builder.register("View", processors);

    }

    public static class AppBarLayoutParamsHelper {
        private static final String SCROLL_FLAG_SCROLL = "scroll";
        private static final String SCROLL_FLAG_EXIT_UNTIL_COLLAPSED = "exitUntilCollapsed";
        private static final String SCROLL_FLAG_ENTER_ALWAYS = "enterAlways";
        private static final String SCROLL_FLAG_ENTER_ALWAYS_COLLAPSED = "enterAlwaysCollapsed";
        private static final String SCROLL_FLAG_SNAP = "snap";

        private static final HashMap<String, Integer> sScrollFlagMap = new HashMap<>();

        private static boolean sInitialized = false;

        private static void initialize() {
            if (!sInitialized) {
                sInitialized = true;

                sScrollFlagMap.put(SCROLL_FLAG_SCROLL, AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL);
                sScrollFlagMap.put(SCROLL_FLAG_EXIT_UNTIL_COLLAPSED, AppBarLayout.LayoutParams.SCROLL_FLAG_EXIT_UNTIL_COLLAPSED);
                sScrollFlagMap.put(SCROLL_FLAG_ENTER_ALWAYS, AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS);
                sScrollFlagMap.put(SCROLL_FLAG_ENTER_ALWAYS_COLLAPSED, AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS_COLLAPSED);
                sScrollFlagMap.put(SCROLL_FLAG_SNAP, AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP);
            }
        }


        private static AppBarLayout.LayoutParams getLayoutParams(View v) {
            initialize();
            AppBarLayout.LayoutParams result = null;
            ViewGroup.LayoutParams layoutParams = v.getLayoutParams();
            if (layoutParams instanceof AppBarLayout.LayoutParams) {
                result = (AppBarLayout.LayoutParams) layoutParams;
            }
            return result;
        }

        public static void setLayoutScrollFlags(View v, String scrollFlags) {
            AppBarLayout.LayoutParams layoutParams = getLayoutParams(v);
            if (null != layoutParams && !TextUtils.isEmpty(scrollFlags)) {
                String[] listFlags = scrollFlags.split("\\|");
                int scrollFlag = 0;
                for (String flag : listFlags) {
                    Integer flags = sScrollFlagMap.get(flag.trim());
                    if (null != flags) {
                        scrollFlag |= flags;
                    }
                }

                if (scrollFlag != 0) {
                    layoutParams.setScrollFlags(scrollFlag);
                }
            }
        }
    }

    public static class CollapsingToolbarLayoutParamsHelper {
        private static final String COLLAPSE_MODE_OFF = "off";
        private static final String COLLAPSE_MODE_PARALLAX = "parallax";
        private static final String COLLAPSE_MODE_PIN = "pin";

        private static final HashMap<String, Integer> sCollapseModeMap = new HashMap<>();

        private static boolean sInitialized = false;

        private static void initialize() {
            if (!sInitialized) {
                sInitialized = true;

                sCollapseModeMap.put(COLLAPSE_MODE_OFF, CollapsingToolbarLayout.LayoutParams.COLLAPSE_MODE_OFF);
                sCollapseModeMap.put(COLLAPSE_MODE_PARALLAX, CollapsingToolbarLayout.LayoutParams.COLLAPSE_MODE_PARALLAX);
                sCollapseModeMap.put(COLLAPSE_MODE_PIN, CollapsingToolbarLayout.LayoutParams.COLLAPSE_MODE_PIN);
            }
        }


        private static CollapsingToolbarLayout.LayoutParams getLayoutParams(View v) {
            initialize();
            CollapsingToolbarLayout.LayoutParams result = null;
            ViewGroup.LayoutParams layoutParams = v.getLayoutParams();
            if (layoutParams instanceof CollapsingToolbarLayout.LayoutParams) {
                result = (CollapsingToolbarLayout.LayoutParams) layoutParams;
            }
            return result;
        }

        public static void setCollapseMode(View v, String mode) {
            CollapsingToolbarLayout.LayoutParams layoutParams = getLayoutParams(v);
            if (null != layoutParams) {
                Integer collapseMode = sCollapseModeMap.get(mode);
                if (null != collapseMode) {
                    layoutParams.setCollapseMode(collapseMode);
                }
            }
        }

        public static void setParallaxMultiplier(View v, String multiplier) {
            CollapsingToolbarLayout.LayoutParams layoutParams = getLayoutParams(v);
            if (null != layoutParams && !TextUtils.isEmpty(multiplier)) {
                layoutParams.setParallaxMultiplier(ParseHelper.parseFloat(multiplier));
            }

        }
    }

    public static class CoordinatorLayoutParamsHelper {
        private static final HashMap<String, Class<?>> sBehaviorMap = new HashMap<>();

        private static CoordinatorLayout.LayoutParams getLayoutParams(View v) {
            CoordinatorLayout.LayoutParams result = null;
            ViewGroup.LayoutParams layoutParams = v.getLayoutParams();
            if (layoutParams instanceof CoordinatorLayout.LayoutParams) {
                result = (CoordinatorLayout.LayoutParams) layoutParams;
            }
            return result;
        }

       public static void setLayoutBehavior(View v, String behavior) {
            CoordinatorLayout.LayoutParams layoutParams = getLayoutParams(v);
            if (null != layoutParams && !TextUtils.isEmpty(behavior)) {

                //noinspection TryWithIdenticalCatches since there are min API requirements
                try {
                    Class<?> clazz = sBehaviorMap.get(behavior);
                    if (null == clazz) {
                        clazz = Class.forName(behavior);
                        sBehaviorMap.put(behavior, clazz);
                    }
                    Object behaviorObj = clazz.newInstance();
                    if (behaviorObj instanceof CoordinatorLayout.Behavior) {
                        layoutParams.setBehavior((CoordinatorLayout.Behavior<?>) behaviorObj);
                    }
                } catch (ClassNotFoundException e) {
                    if (ProteusConstants.isLoggingEnabled()) {
                        e.printStackTrace();
                    }
                } catch (InstantiationException e) {
                    if (ProteusConstants.isLoggingEnabled()) {
                        e.printStackTrace();
                    }
                } catch (IllegalAccessException e) {
                    if (ProteusConstants.isLoggingEnabled()) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }
}
