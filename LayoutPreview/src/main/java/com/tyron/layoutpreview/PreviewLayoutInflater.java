package com.tyron.layoutpreview;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;
import android.view.ContextThemeWrapper;
import android.view.InflateException;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.internal.ThemeEnforcement;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Objects;

public class PreviewLayoutInflater extends LayoutInflater {

    private static final String[] sClassPrefixList = {
            "android.widget.",
            "android.webkit.",
            "android.app."
    };
    private static final String TAG = PreviewLayoutInflater.class.getSimpleName();

    private static final HashMap<String, Constructor<? extends View>> sConstructorMap =
            new HashMap<String, Constructor<? extends View>>();
   
    final Object[] mConstructorArgs = new Object[2];

    static final Class<?>[] mConstructorSignature = new Class[] {
            Context.class, AttributeSet.class};


    public PreviewLayoutInflater(PreviewContext context) {
        super(context);
    }

    @Override
    public LayoutInflater cloneInContext(Context context) {
        if (!(context instanceof PreviewContext)) {
            return null;
        }
        return new PreviewLayoutInflater((PreviewContext) context);
    }

    @Override protected View onCreateView(String name, AttributeSet attrs) throws ClassNotFoundException {
        for (String prefix : sClassPrefixList) {
            try {
                View view = createView(name, prefix, attrs);
                if (view != null) {
                    return view;
                }
            } catch (ClassNotFoundException e) {
                // In this case we want to let the base class take a crack
                // at it.
            }

            if (name.equals(AppBarLayout.class.getName())) {
                return new AppBarLayout(getContext() ,attrs);
            }
        }
        return super.onCreateView(name, attrs);
    }

    @Override
    public View inflate(int resource, @Nullable ViewGroup root, boolean attachToRoot) {
        final Resources res = getContext().getResources();

        XmlResourceParser parser = res.getLayout(resource);
        try {
            return inflate(parser, root, attachToRoot);
        } finally {
            parser.close();
        }
    }

    private void advanceToRootNode(XmlPullParser parser)
            throws InflateException, IOException, XmlPullParserException {
        // Look for the root node.
        int type;
        while ((type = parser.next()) != XmlPullParser.START_TAG &&
                type != XmlPullParser.END_DOCUMENT) {
            // Empty
        }
        if (type != XmlPullParser.START_TAG) {
            throw new InflateException(parser.getPositionDescription()
                    + ": No start tag found!");
        }
    }

    @Override
    public View inflate(XmlPullParser parser, @Nullable ViewGroup root, boolean attachToRoot) {
        synchronized (mConstructorArgs) {
            final Context inflaterContext = getContext();
            Log.d(TAG, parser.toString());
            final AttributeSet attrs = Xml.asAttributeSet(parser);

            Context lastContext = (Context) mConstructorArgs[0];
            mConstructorArgs[0] = inflaterContext;
            View result = root;

            try {
                advanceToRootNode(parser);
                final String name = parser.getName();

//                if (TAG_MERGE.equals(name)) {
//                    if (root == null || !attachToRoot) {
//                        throw new InflateException("<merge /> can be used only with a valid "
//                                + "ViewGroup root and attachToRoot=true");
//                    }
//                    rInflate(parser, root, inflaterContext, attrs, false);
//                } else {

                final View temp = createViewFromTag(root, name, inflaterContext, attrs);

                ViewGroup.LayoutParams params = null;
                if (root != null) {
                    // Create layout params that match root, if supplied
                    params = root.generateLayoutParams(attrs);
                    if (!attachToRoot) {
                        // Set the layout params for temp if we are not
                        // attaching. (If we are, we use addView, below)
                        temp.setLayoutParams(params);
                    }
                }
                // Inflate all children under temp against its context.
                rInflateChildren(parser, temp, attrs, true);

                // We are supposed to attach all the views we found (int temp)
                // to root. Do that now.
                if (root != null && attachToRoot) {
                    root.addView(temp, params);
                }
                // Decide whether to return the root that was passed in or the
                // top view found in xml.
                if (root == null || !attachToRoot) {
                    result = temp;
                }
            } catch (XmlPullParserException | IOException e) {
                Log.d(TAG, e.getMessage());
            }
            return result;
        }
    }


    final void rInflateChildren(XmlPullParser parser, View parent, AttributeSet attrs,
                                boolean finishInflate) throws XmlPullParserException, IOException {
        rInflate(parser, parent, getContext(), attrs, finishInflate);
    }

    void rInflate(XmlPullParser parser, View parent, Context context,
                  AttributeSet attrs, boolean finishInflate) throws XmlPullParserException, IOException {
        final int depth = parser.getDepth();
        int type;
        boolean pendingRequestFocus = false;
        while (((type = parser.next()) != XmlPullParser.END_TAG ||
                parser.getDepth() > depth) && type != XmlPullParser.END_DOCUMENT) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }
            final String name = parser.getName();
//            if (TAG_REQUEST_FOCUS.equals(name)) {
//                pendingRequestFocus = true;
//                consumeChildElements(parser);
//            } else if (TAG_TAG.equals(name)) {
//                parseViewTag(parser, parent, attrs);
//            } else if (TAG_INCLUDE.equals(name)) {
//                if (parser.getDepth() == 0) {
//                    throw new InflateException("<include /> cannot be the root element");
//                }
//                parseInclude(parser, context, parent, attrs);
//            } else if (TAG_MERGE.equals(name)) {
//                throw new InflateException("<merge /> must be the root element");
//            } else {
                final View view = createViewFromTag((ViewGroup) parent, name, context, attrs);
                final ViewGroup viewGroup = (ViewGroup) parent;
                final ViewGroup.LayoutParams params = viewGroup.generateLayoutParams(attrs);
                rInflateChildren(parser, view, attrs, true);
                viewGroup.addView(view, params);
         //   }
        }
        if (pendingRequestFocus) {
            parent.restoreDefaultFocus();
        }
    }


    private View createViewFromTag(ViewGroup parent, String name, Context context, AttributeSet attrs) {
        return createViewFromTag(parent, name, context, attrs, false);
    }

    private  View createViewFromTag(View parent, String name, Context context, AttributeSet attrs,
                                    boolean ignoreThemeAttr) {
        if (name.equals("view")) {
            name = attrs.getAttributeValue(null, "class");
        }

        // Apply a theme wrapper, if allowed and one is specified.
        if (!ignoreThemeAttr) {

        }
        try {
            View view = null;
            final Object lastContext = mConstructorArgs[0];
            mConstructorArgs[0] = context;
            try {
                if (-1 == name.indexOf('.')) {
                    view = onCreateView(context, parent, name, attrs);
                } else {
                    try {
                        view = createViewInternal(context, name, null, attrs);
                    } catch (RuntimeException e) {
                        Log.d(TAG, "Failed to inflate layout using custom context, retrying with base context");
                        // the custom context failed, try if we can inflate using our own context
                        mConstructorArgs[0] = ((ContextWrapper) getContext()).getBaseContext();
                        view = createViewInternal((Context) mConstructorArgs[0], name, null, attrs);
                    }
                }
            } finally {
                mConstructorArgs[0] = lastContext;
            }
            return view;
        } catch (InflateException e) {
            throw e;
        } catch (ClassNotFoundException e) {
            throw new InflateException(e);
        }
    }

    @Nullable
    public View onCreateView(@NonNull Context viewContext, @Nullable View parent,
                             @NonNull String name, @Nullable AttributeSet attrs)
            throws ClassNotFoundException {
        return onCreateView(parent, name, attrs);
    }

    public View createViewInternal(@NonNull Context viewContext, @NonNull String name,
                                 @Nullable String prefix, @Nullable AttributeSet attrs)
            throws ClassNotFoundException, InflateException {
        Objects.requireNonNull(viewContext);
        Objects.requireNonNull(name);



        Constructor<? extends View> constructor = sConstructorMap.get(name);
        if (constructor != null && !verifyClassLoader(constructor)) {
            constructor = null;
            sConstructorMap.remove(name);
        }
        Class<? extends View> clazz = null;
        try {
            if (constructor == null) {
                Class<? > clazzTest = viewContext.getClassLoader().loadClass(prefix != null ? (prefix + name) : name);
              if (clazzTest != null) {
                  clazz = clazzTest.asSubclass(View.class);
              } else {
                  // Class not found in the cache, see if it's real, and try to add it
                  clazz = Class.forName(prefix != null ? (prefix + name) : name, false,
                          viewContext.getClassLoader()).asSubclass(View.class);
              }
//                if (mFilter != null && clazz != null) {
//                    boolean allowed = mFilter.onLoadClass(clazz);
//                    if (!allowed) {
//                        failNotAllowed(name, prefix, viewContext, attrs);
//                    }
//                }
                constructor = clazz.getConstructor(mConstructorSignature);
                constructor.setAccessible(true);
                sConstructorMap.put(name, constructor);
            } else {
                // If we have a filter, apply it to cached constructor
//                if (mFilter != null) {
//                    // Have we seen this name before?
//                    Boolean allowedState = mFilterMap.get(name);
//                    if (allowedState == null) {
//                        // New class -- remember whether it is allowed
//                        clazz = Class.forName(prefix != null ? (prefix + name) : name, false,
//                                getContext().getClassLoader()).asSubclass(View.class);
//                        boolean allowed = clazz != null && mFilter.onLoadClass(clazz);
//                        mFilterMap.put(name, allowed);
//                        if (!allowed) {
//                            failNotAllowed(name, prefix, viewContext, attrs);
//                        }
//                    } else if (allowedState.equals(Boolean.FALSE)) {
//                        failNotAllowed(name, prefix, viewContext, attrs);
//                    }
//                }
            }
            Object lastContext = mConstructorArgs[0];
            mConstructorArgs[0] = viewContext;
            Object[] args = mConstructorArgs;
            args[1] = attrs;

            try {
                final View view = constructor.newInstance(args);
                if (view instanceof ViewStub) {
                    // Use the same context when inflating ViewStub later.
                    final ViewStub viewStub = (ViewStub) view;
                    viewStub.setLayoutInflater(cloneInContext((Context) args[0]));
                }
                return view;
            } finally {
                mConstructorArgs[0] = lastContext;
            }
        } catch (ClassNotFoundException e) {
            // If loadClass fails, we should propagate the exception.
            throw e;
        } catch (Exception e) {
            // If loaded class is not a View subclass
            throw new InflateException(e);
        }
    }

    private final boolean verifyClassLoader(Constructor<? extends View> constructor) {
        final ClassLoader constructorLoader = constructor.getDeclaringClass().getClassLoader();
        // in all normal cases (no dynamic code loading), we will exit the following loop on the
        // first iteration (i.e. when the declaring classloader is the contexts class loader).
        ClassLoader cl = getContext().getClassLoader();
        do {
            if (constructorLoader == cl) {
                return true;
            }
            cl = cl.getParent();
        } while (cl != null);
        return false;
    }



}
