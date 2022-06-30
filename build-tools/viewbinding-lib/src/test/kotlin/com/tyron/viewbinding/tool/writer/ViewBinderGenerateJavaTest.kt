package com.tyron.viewbinding.tool.writer

import com.tyron.viewbinding.tool.LayoutResourceRule
import com.tyron.viewbinding.tool.assert
import com.tyron.viewbinding.tool.processing.ScopedException
import com.google.common.truth.Truth.assertThat
import com.tyron.viewbinding.tool.store.LayoutFileParser.DATA_BINDING_NOT_IMPLEMENTED_MESSAGE
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

class ViewBinderGenerateJavaTest {
    @get:Rule val layouts = LayoutResourceRule(viewBindingEnabled = true)

    @Test fun nullableFieldsJavadocTheirConfigurations() {
        layouts.write("example", "layout", """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <TextView android:id="@+id/name" />
            </LinearLayout>
            """.trimIndent())

        layouts.write("example", "layout-sw600dp", """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <TextView android:id="@+id/name" />
            </LinearLayout>
            """.trimIndent())

        layouts.write("example", "layout-land", """
            <LinearLayout />
            """.trimIndent())

        val model = layouts.parse().getValue("example")
        model.toViewBinder().toJavaFile().assert {
            contains("""
                |  /**
                |   * This binding is not available in all configurations.
                |   * <p>
                |   * Present:
                |   * <ul>
                |   *   <li>layout/</li>
                |   *   <li>layout-sw600dp/</li>
                |   * </ul>
                |   *
                |   * Absent:
                |   * <ul>
                |   *   <li>layout-land/</li>
                |   * </ul>
                |   */
                |  @Nullable
                |  public final TextView name;
                """.trimMargin())
        }
    }

    @Test fun zeroBindingsDoesNotGenerateErrorHandling() {
        layouts.write("example", "layout", "<View />")

        val model = layouts.parse().getValue("example")
        model.toViewBinder().toJavaFile().assert {
            parsesAs("""
                |package com.example.databinding;
                |
                |import android.view.LayoutInflater;
                |import android.view.View;
                |import android.view.ViewGroup;
                |import androidx.annotation.NonNull;
                |import androidx.annotation.Nullable;
                |import androidx.viewbinding.ViewBinding;
                |import com.example.R;
                |import java.lang.NullPointerException;
                |import java.lang.Override;
                |
                |public final class ExampleBinding implements ViewBinding {
                |  @NonNull
                |  private final View rootView;
                |
                |  private ExampleBinding(@NonNull View rootView) {
                |    this.rootView = rootView;
                |  }
                |
                |  @Override
                |  @NonNull
                |  public View getRoot() {
                |    return rootView;
                |  }
                |
                |  @NonNull
                |  public static ExampleBinding inflate(@NonNull LayoutInflater inflater) {
                |    return inflate(inflater, null, false);
                |  }
                |
                |  @NonNull
                |  public static ExampleBinding inflate(@NonNull LayoutInflater inflater,
                |      @Nullable ViewGroup parent, boolean attachToParent) {
                |    View root = inflater.inflate(R.layout.example, parent, false);
                |    if (attachToParent) {
                |      parent.addView(root);
                |    }
                |    return bind(root);
                |  }
                |
                |  @NonNull
                |  public static ExampleBinding bind(@NonNull View rootView) {
                |    if (rootView == null) {
                |      throw new NullPointerException("rootView");
                |    }
                |    return new ExampleBinding(rootView);
                |  }
                |}
            """.trimMargin())
        }
    }

    @Test fun allOptionalBindingsDoesNotGenerateErrorHandling() {
        layouts.write("example", "layout", """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <TextView android:id="@+id/name" />
                <TextView android:id="@+id/email" />
            </LinearLayout>
            """.trimIndent())

        layouts.write("example", "layout-sw600dp", """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <TextView android:id="@+id/name" />
            </LinearLayout>
            """.trimIndent())

        layouts.write("example", "layout-land", """
            <LinearLayout />
            """.trimIndent())

        val model = layouts.parse().getValue("example")
        model.toViewBinder().toJavaFile().assert {
            parsesAs("""
                |package com.example.databinding;
                |
                |import android.view.LayoutInflater;
                |import android.view.View;
                |import android.view.ViewGroup;
                |import android.widget.LinearLayout;
                |import android.widget.TextView;
                |import androidx.annotation.NonNull;
                |import androidx.annotation.Nullable;
                |import androidx.viewbinding.ViewBinding;
                |import androidx.viewbinding.ViewBindings;
                |import com.example.R;
                |import java.lang.Override;
                |
                |public final class ExampleBinding implements ViewBinding {
                |  @NonNull
                |  private final LinearLayout rootView;
                |
                |  @Nullable
                |  public final TextView email;
                |
                |  @Nullable
                |  public final TextView name;
                |
                |  private ExampleBinding(@NonNull LinearLayout rootView, @Nullable TextView email,
                |      @Nullable TextView name) {
                |    this.rootView = rootView;
                |    this.email = email;
                |    this.name = name;
                |  }
                |
                |  @Override
                |  @NonNull
                |  public LinearLayout getRoot() {
                |    return rootView;
                |  }
                |
                |  @NonNull
                |  public static ExampleBinding inflate(@NonNull LayoutInflater inflater) {
                |    return inflate(inflater, null, false);
                |  }
                |
                |  @NonNull
                |  public static ExampleBinding inflate(@NonNull LayoutInflater inflater,
                |      @Nullable ViewGroup parent, boolean attachToParent) {
                |    View root = inflater.inflate(R.layout.example, parent, false);
                |    if (attachToParent) {
                |      parent.addView(root);
                |    }
                |    return bind(root);
                |  }
                |
                |  @NonNull
                |  public static ExampleBinding bind(@NonNull View rootView) {
                |    TextView email = ViewBindings.findChildViewById(rootView, R.id.email);
                |    TextView name = ViewBindings.findChildViewById(rootView, R.id.name);
                |    return new ExampleBinding((LinearLayout) rootView, email, name);
                |  }
                |}
            """.trimMargin())
        }
    }

    @Test fun bindingNameCollisions() {
        layouts.write("other", "layout", "<FrameLayout/>")
        layouts.write("example", "layout", """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <TextView android:id="@+id/root_view" />
                <TextView android:id="@+id/missing_id" />
                <View android:id="@+id/id" />
            </LinearLayout>
            """.trimIndent())

        val model = layouts.parse().getValue("example")
        model.toViewBinder().toJavaFile().assert {
            parsesAs("""
                |package com.example.databinding;
                |
                |import android.view.LayoutInflater;
                |import android.view.View;
                |import android.view.ViewGroup;
                |import android.widget.LinearLayout;
                |import android.widget.TextView;
                |import androidx.annotation.NonNull;
                |import androidx.annotation.Nullable;
                |import androidx.viewbinding.ViewBinding;
                |import androidx.viewbinding.ViewBindings;
                |import com.example.R;
                |import java.lang.NullPointerException;
                |import java.lang.Override;
                |import java.lang.String;
                |
                |public final class ExampleBinding implements ViewBinding {
                |  @NonNull
                |  private final LinearLayout rootView_;
                |
                |  @NonNull
                |  public final View id;
                |
                |  @NonNull
                |  public final TextView missingId;
                |
                |  @NonNull
                |  public final TextView rootView;
                |
                |  private ExampleBinding(@NonNull LinearLayout rootView_, @NonNull View id,
                |      @NonNull TextView missingId, @NonNull TextView rootView) {
                |    this.rootView_ = rootView_;
                |    this.id = id;
                |    this.missingId = missingId;
                |    this.rootView = rootView;
                |  }
                |
                |  @Override
                |  @NonNull
                |  public LinearLayout getRoot() {
                |    return rootView_;
                |  }
                |
                |  @NonNull
                |  public static ExampleBinding inflate(@NonNull LayoutInflater inflater) {
                |    return inflate(inflater, null, false);
                |  }
                |
                |  @NonNull
                |  public static ExampleBinding inflate(@NonNull LayoutInflater inflater,
                |      @Nullable ViewGroup parent, boolean attachToParent) {
                |    View root = inflater.inflate(R.layout.example, parent, false);
                |    if (attachToParent) {
                |      parent.addView(root);
                |    }
                |    return bind(root);
                |  }
                |
                |  @NonNull
                |  public static ExampleBinding bind(@NonNull View rootView) {
                |    int id;
                |    missingId: {
                |      id = R.id.id;
                |      View id_ = ViewBindings.findChildViewById(rootView, id);
                |      if (id_ == null) {
                |        break missingId;
                |      }
                |
                |      id = R.id.missing_id;
                |      TextView missingId = ViewBindings.findChildViewById(rootView, id);
                |      if (missingId == null) {
                |        break missingId;
                |      }
                |
                |      id = R.id.root_view;
                |      TextView rootView_ = ViewBindings.findChildViewById(rootView, id);
                |      if (rootView_ == null) {
                |        break missingId;
                |      }
                |
                |      return new ExampleBinding((LinearLayout) rootView, id_, missingId,
                |          rootView_);
                |    }
                |
                |    String missingId_ = rootView.getResources().getResourceName(id);
                |    throw new NullPointerException(
                |        "Missing required view with ID: ".concat(missingId_));
                |  }
                |}
            """.trimMargin())
        }
    }

    @Test fun ignoreLayoutTruthyValues() {
        layouts.write("example1", "layout", """
            <LinearLayout
                    xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:tools="http://schemas.android.com/tools"
                    tools:viewBindingIgnore="true"
                    />
            """.trimIndent())
        layouts.write("example2", "layout", """
            <LinearLayout
                    xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:tools="http://schemas.android.com/tools"
                    tools:viewBindingIgnore="TRUE"
                    />
            """.trimIndent())
        layouts.write("example3", "layout", """
            <LinearLayout
                    xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:tools="http://schemas.android.com/tools"
                    tools:viewBindingIgnore="tRuE"
                    />
            """.trimIndent())

        assertThat(layouts.parse()).apply {
            doesNotContainKey("example1")
            doesNotContainKey("example2")
            doesNotContainKey("example3")
        }
    }

    @Test fun ignoreLayoutFalseyValues() {
        layouts.write("example1", "layout", """
            <LinearLayout
                    xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:tools="http://schemas.android.com/tools"
                    tools:viewBindingIgnore="false"
                    />
            """.trimIndent())
        layouts.write("example2", "layout", """
            <LinearLayout
                    xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:tools="http://schemas.android.com/tools"
                    tools:viewBindingIgnore="yes"
                    />
            """.trimIndent())
        layouts.write("example3", "layout", """
            <LinearLayout
                    xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:tools="http://schemas.android.com/tools"
                    tools:viewBindingIgnore="   true        "
                    />
            """.trimIndent())
        layouts.write("example4", "layout", """
            <LinearLayout
                    xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:tools="http://schemas.android.com/tools"
                    tools:viewBindingIgnore=""
                    />
            """.trimIndent())

        assertThat(layouts.parse()).apply {
            containsKey("example1")
            containsKey("example2")
            containsKey("example3")
            containsKey("example4")
        }
    }

    @Test fun ignoreLayoutSingleConfiguration() {
        layouts.write("example", "layout", """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <TextView android:id="@+id/name" />
            </LinearLayout>
            """.trimIndent())

        layouts.write("example", "layout-land", """
            <LinearLayout
                    xmlns:tools="http://schemas.android.com/tools"
                    tools:viewBindingIgnore="true"
                    />
            """.trimIndent())

        val model = layouts.parse().getValue("example")

        // This would create a @Nullable field if the second layout was parsed.
        model.toViewBinder().toJavaFile().assert {
            contains("""
                |  @NonNull
                |  public final TextView name;
                """.trimMargin())
        }
    }

    @Test fun mergeRemovesSingleArgumentInflateAndAttachParam() {
        layouts.write("example", "layout", "<merge/>")

        val model = layouts.parse().getValue("example")
        model.toViewBinder().toJavaFile().assert {
            parsesAs("""
                |package com.example.databinding;
                |
                |import android.view.LayoutInflater;
                |import android.view.View;
                |import android.view.ViewGroup;
                |import androidx.annotation.NonNull;
                |import androidx.viewbinding.ViewBinding;
                |import com.example.R;
                |import java.lang.NullPointerException;
                |import java.lang.Override;
                |
                |public final class ExampleBinding implements ViewBinding {
                |  @NonNull
                |  private final View rootView;
                |
                |  private ExampleBinding(@NonNull View rootView) {
                |    this.rootView = rootView;
                |  }
                |
                |  @Override
                |  @NonNull
                |  public View getRoot() {
                |    return rootView;
                |  }
                |
                |  @NonNull
                |  public static ExampleBinding inflate(@NonNull LayoutInflater inflater,
                |      @NonNull ViewGroup parent) {
                |    if (parent == null) {
                |      throw new NullPointerException("parent");
                |    }
                |    inflater.inflate(R.layout.example, parent);
                |    return bind(parent);
                |  }
                |
                |  @NonNull
                |  public static ExampleBinding bind(@NonNull View rootView) {
                |    if (rootView == null) {
                |      throw new NullPointerException("rootView");
                |    }
                |    return new ExampleBinding(rootView);
                |  }
                |}
            """.trimMargin())
        }
    }

    @Test fun configurationsMustAgreeOnRootMergeTag() {
        layouts.write("example", "layout", "<merge/>")
        layouts.write("example", "layout-land", "<FrameLayout/>")
        layouts.write("example", "layout-sw600dp", "<FrameLayout/>")

        val model = layouts.parse().getValue("example")
        try {
            model.toViewBinder()
            fail()
        } catch (e: IllegalStateException) {
            assertThat(e).hasMessageThat().isEqualTo("""
                Configurations for example.xml must agree on the use of a root <merge> tag.

                Present:
                 - layout

                Absent:
                 - layout-sw600dp
                 - layout-land
                """.trimIndent()
            )
        }
    }

    @Test fun matchingRootViewsGetCovariantRootReturnType() {
        layouts.write("example", "layout", "<LinearLayout/>")
        layouts.write("example", "layout-land", "<LinearLayout/>")

        val model = layouts.parse().getValue("example")

        model.toViewBinder().toJavaFile().assert {
            contains("""
                |  public LinearLayout getRoot() {
                """.trimMargin())
        }
    }

    @Test fun matchingRootViewsWithDifferentDeclarationsGetCovariantRootReturnType() {
        layouts.write("example", "layout", "<LinearLayout/>")
        layouts.write("example", "layout-land", "<android.widget.LinearLayout/>")
        layouts.write("example", "layout-sw600dp", """<view class="android.widget.LinearLayout"/>""")

        val model = layouts.parse().getValue("example")

        model.toViewBinder().toJavaFile().assert {
            contains("""
                |  public LinearLayout getRoot() {
                """.trimMargin())
        }
    }

    @Test fun conflictingRootViewsDoNotGetCovariantRootReturnType() {
        layouts.write("example", "layout", "<LinearLayout/>")
        layouts.write("example", "layout-land", "<FrameLayout/>")

        val model = layouts.parse().getValue("example")

        model.toViewBinder().toJavaFile().assert {
            contains("""
                |  public View getRoot() {
                """.trimMargin())
        }
    }

    @Test fun mergeRootViewsDoNotGetCovariantRootReturnType() {
        layouts.write("example", "layout", """
            <merge xmlns:android="http://schemas.android.com/apk/res/android">
                <TextView android:id="@+id/name" />
            </merge>
            """.trimIndent())

        val model = layouts.parse().getValue("example")

        model.toViewBinder().toJavaFile().assert {
            contains("""
                |  public View getRoot() {
                """.trimMargin())
        }
    }

    @Test fun optionalIncludeConditionallyCallsBind() {
        layouts.write("other", "layout", "<FrameLayout/>")
        layouts.write("example", "layout", """
            <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <include
                    android:id="@+id/other"
                    layout="@layout/other"
                    />
            </FrameLayout>
        """.trimIndent())
        layouts.write("example", "layout-land", "<FrameLayout/>")

        val model = layouts.parse().getValue("example")
        model.toViewBinder().toJavaFile().assert {
            contains("""
                |    View other = ViewBindings.findChildViewById(rootView, R.id.other);
                |    OtherBinding binding_other = other != null
                |        ? OtherBinding.bind(other)
                |        : null;
            """.trimMargin())
        }
    }

    @Test fun rootNodeAgreeingOnIdDoesNotCallFindViewById() {
        layouts.write("example", "layout", """
            <View
                xmlns:android="http://schemas.android.com/apk/res/android"
                android:id="@+id/root"
                />
        """.trimIndent())

        val model = layouts.parse().getValue("example")
        model.toViewBinder().toJavaFile().assert {
            contains("View root = rootView;")
        }
    }

    @Test fun rootNodeAgreeingOnIdDoesNotCallFindViewByIdAndCastsWhenNeeded() {
        layouts.write("example", "layout", """
            <FrameLayout
                xmlns:android="http://schemas.android.com/apk/res/android"
                android:id="@+id/root"
                />
        """.trimIndent())

        val model = layouts.parse().getValue("example")
        model.toViewBinder().toJavaFile().assert {
            contains("FrameLayout root = (FrameLayout) rootView;")
        }
    }

    @Test fun rootNodeDisagreeingOnIdFails() {
        layouts.write("example", "layout", """
            <FrameLayout
                xmlns:android="http://schemas.android.com/apk/res/android"
                android:id="@+id/one"
                />
        """.trimIndent())
        layouts.write("example", "layout-land", """
            <FrameLayout
                xmlns:android="http://schemas.android.com/apk/res/android"
                android:id="@+id/two"
                />
        """.trimIndent())

        val model = layouts.parse().getValue("example")
        try {
            model.toViewBinder()
        } catch (e: IllegalStateException) {
            assertThat(e).hasMessageThat().isEqualTo("""
                Configurations for example.xml must agree on the root element's ID.

                @+id/one:
                 - layout

                @+id/two:
                 - layout-land
                """.trimIndent()
            )
        }
    }

    @Test fun rootNodePartialIdFails() {
        layouts.write("example", "layout", """
            <FrameLayout
                xmlns:android="http://schemas.android.com/apk/res/android"
                android:id="@+id/partial"
                />
        """.trimIndent())
        layouts.write("example", "layout-land", "<FrameLayout/>")

        val model = layouts.parse().getValue("example")
        try {
            model.toViewBinder()
            fail()
        } catch (e: IllegalStateException) {
            assertThat(e).hasMessageThat().isEqualTo("""
                Configurations for example.xml must agree on the root element's ID.

                Missing ID:
                 - layout-land

                @+id/partial:
                 - layout
                """.trimIndent()
            )
        }
    }

    @Test fun invalidNodeNameFailsWithNiceMessage() {
        layouts.write("example", "layout", """
            <layer-list xmlns:android="http://schemas.android.com/apk/res/android"/>
        """.trimIndent())

        val model = layouts.parse().getValue("example")
        try {
            model.toViewBinder()
            fail()
        } catch (e: IllegalArgumentException) {
            assertThat(e).hasMessageThat()
                .isEqualTo("Unable to parse \"android.widget.layer-list\" as class in example.xml")
        }
    }

    @Test fun fragmentNodesAreNotExposed() {
        layouts.write("as_root", "layout", """
            <fragment
                xmlns:android="http://schemas.android.com/apk/res/android"
                android:id="@+id/fragment"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                />
        """.trimIndent())
        layouts.write("as_child", "layout", """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <View
                    android:id="@+id/one"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    />
                <fragment
                    class="android.app.Fragment"
                    android:id="@+id/two"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    />
            </LinearLayout>
        """.trimIndent())

        val fragmentAsRootModel = layouts.parse().getValue("as_root")
        fragmentAsRootModel.toViewBinder().toJavaFile().assert {
            contains("View getRoot()")
            doesNotContain("fragment")
        }

        val fragmentAsChildModel = layouts.parse().getValue("as_child")
        fragmentAsChildModel.toViewBinder().toJavaFile().assert {
            contains("one")
            doesNotContain("two")
        }
    }

    @Test fun mergeWithIdAndInclude() {
        // https://issuetracker.google.com/154747638
        // Note: This bug only manifests when there's a nested <include> the <merge>.

        layouts.write("simple", "layout", "<View/>")
        layouts.write("merge_with_id", "layout", """
            <merge
                xmlns:android="http://schemas.android.com/apk/res/android"
                android:id="@+id/main_content"
                >
                <include layout="@layout/simple"/>
            </merge>
        """.trimIndent())

        val mergeWithId = layouts.parse().getValue("merge_with_id")
        mergeWithId.toViewBinder().toJavaFile().assert {
            doesNotContain("mainContent")
        }
    }

    @Test fun layoutXmlWhenViewBindingFails() {
        layouts.write("example", "layout", """
            <layout xmlns:android="http://schemas.android.com/apk/res/android">
                <TextView/>
            </layout>
        """.trimIndent())

        try {
            layouts.parse()
            fail()
        } catch (e: ScopedException) {
            assertThat(e).hasMessageThat().contains(DATA_BINDING_NOT_IMPLEMENTED_MESSAGE)
        }
    }
}
