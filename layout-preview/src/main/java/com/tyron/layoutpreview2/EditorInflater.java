package com.tyron.layoutpreview2;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.builder.compiler.manifest.SdkConstants;
import com.tyron.common.logging.IdeLog;
import com.tyron.xml.completion.repository.Repository;
import com.tyron.xml.completion.util.DOMUtils;
import com.tyron.layoutpreview2.manager.ViewManagerImpl;
import com.tyron.layoutpreview2.util.ViewGroupUtils;
import com.tyron.layoutpreview2.view.EditorView;

import org.eclipse.lemminx.dom.DOMAttr;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMNode;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class EditorInflater {

    private static final Logger sLogger = IdeLog.getCurrentLogger(EditorInflater.class);

    private static final HashMap<String, Constructor<? extends View>> sConstructorMap =
            new HashMap<String, Constructor<? extends View>>();

    static final Class<?>[] mConstructorSignature = new Class[]{Context.class};
    private static final StackTraceElement[] EMPTY_STACK_TRACE = new StackTraceElement[0];

    final Object[] mConstructorArgs = new Object[2];

    private final EditorContext mContext;

    public EditorInflater(EditorContext context) {
        mContext = context;
    }

    public View inflate(@NonNull DOMDocument document,
                        @Nullable ViewGroup rootView,
                        boolean attachToRoot) {
        List<DOMNode> roots = DOMUtils.getRootElements(document);
        if (roots.isEmpty()) {
            throw new InflateException("No root element found.");
        }
        if (roots.size() > 1) {
            throw new InflateException("Document has more than 1 root.");
        }

        final Context inflaterContext = mContext;
        EditorContext lastContext = (EditorContext) mConstructorArgs[0];
        mConstructorArgs[0] = inflaterContext;
        View result = rootView;

        DOMNode root = roots.get(0);
        if (!(root instanceof DOMElement)) {
            throw new InflateException("Root is not a DOMElement");
        }
        DOMElement rootElement = ((DOMElement) root);
        String tag = rootElement.getTagName();


        if (SdkConstants.VIEW_MERGE.equals(tag)) {
            throw new UnsupportedOperationException("Merge not yet supported.");
        } else {
            // the root view that is found from xml
            final EditorView tempEditorView = createViewFromTag(rootView, rootElement, inflaterContext);

            final View temp = tempEditorView.getAsView();

            ViewGroup.LayoutParams params = null;
            if (rootView != null) {
                params = ViewGroupUtils.generateDefaultLayoutParams(rootView);
                if (!attachToRoot) {
                    temp.setLayoutParams(params);
                }
            }

            rInflateChildren(rootElement, temp, false);

            tempEditorView.getViewManager().updateAttributes(rootElement.getAttributeNodes());

            if (rootView == null || !attachToRoot) {
                result = temp;
            }
        }
        return result;
    }

    void rInflateChildren(@NonNull DOMElement element,
                          @NonNull View parent,
                          boolean finishInflate) {
        rInflate(element, parent, parent.getContext(), finishInflate);
    }

    void rInflate(DOMElement node, View parent, Context context, boolean finishInflate) {
        List<DOMNode> children = node.getChildren();
        if (children == null || children.isEmpty()) {
            return;
        }

        List<DOMElement> elements = children.stream()
                .filter(it -> it instanceof DOMElement)
                .map(it -> (DOMElement) it)
                .collect(Collectors.toList());
        for (DOMElement element : elements) {
            final String tag = element.getTagName();

            if (SdkConstants.REQUEST_FOCUS.equals(tag)) {
                throw new UnsupportedOperationException("TODO");
            } else if (SdkConstants.TAG.equals(tag)) {
                throw new UnsupportedOperationException("TODO");
            } else if (SdkConstants.FN_FRAMEWORK_INCLUDE.equals(tag)) {
                throw new UnsupportedOperationException("TODO");
            } else if (SdkConstants.VIEW_MERGE.equals(tag)) {
                throw new UnsupportedOperationException("TODO");
            } else {
                final EditorView view = createViewFromTag(parent, element, context);
                final ViewGroup viewGroup = (ViewGroup) parent;
                final ViewGroup.LayoutParams params = ViewGroupUtils.generateDefaultLayoutParams(viewGroup);
                rInflateChildren(element, view.getAsView(), true);
                viewGroup.addView(view.getAsView(), params);

                view.getViewManager().updateAttributes(element.getAttributeNodes());
            }
        }
    }


    public EditorView createViewFromTag(@Nullable View parent,
                                  @NonNull DOMElement element,
                                  @NonNull Context context) {
        return createViewFromTag(parent, element, context, false);
    }

    public EditorView createViewFromTag(@Nullable View parent,
                                        @NonNull DOMElement element,
                                        @NonNull Context context,
                                        boolean ignoreThemeAttr) {
        List<DOMAttr> attrs = element.getAttributeNodes();
        String name = element.getTagName();
        if (SdkConstants.VIEW_TAG.equals(name)) {
            name = element.getAttributeNS(null, "class");
        }

        if (name.equals("blink")) {
            throw new UnsupportedOperationException("TODO");
        }

        try {
            EditorView view;
            final Object lastContext = mConstructorArgs[0];
            mConstructorArgs[0] = context;
            try {
                if (-1 == name.indexOf('.')) {
                    view = onCreateView(parent, name, attrs);
                } else {
                    view = createView(name, null, attrs);
                }
            } finally {
                mConstructorArgs[0] = lastContext;
            }
            return view;
        } catch (ClassNotFoundException e) {
            final InflateException exception = new InflateException(e);
            exception.setStackTrace(new StackTraceElement[0]);
            throw exception;
        }
    }

    protected EditorView onCreateView(String name, List<DOMAttr> attrs) throws ClassNotFoundException {
        return createView(name, "android.view.", attrs);
    }

    /**
     * Version of {@link #onCreateView(String, List)} that also
     * takes the future parent of the view being constructed.  The default
     * implementation simply calls {@link #onCreateView(String, List)}.
     *
     * @param parent The future parent of the returned view.  <em>Note that
     *               this may be null.</em>
     * @param name   The fully qualified class name of the View to be create.
     * @param attrs  An AttributeSet of attributes to apply to the View.
     * @return View The View created.
     */
    protected EditorView onCreateView(View parent,
                                String name,
                                List<DOMAttr> attrs) throws ClassNotFoundException {
        return onCreateView(name, attrs);
    }

    protected final EditorView createView(@NonNull String name,
                                    String prefix,
                                    List<DOMAttr> attrs) throws ClassNotFoundException,
            InflateException {
        Constructor<? extends View> constructor = sConstructorMap.get(name);
        if (constructor != null && !verifyClassLoader(constructor)) {
            constructor = null;
            sConstructorMap.remove(name);
        }

        Class<? extends View> clazz = null;
        String fqn = prefix != null ? (prefix + name) : name;
        String replaced = replaceFqn(fqn);
        if (replaced != null) {
            fqn = replaced;
        }

        try {
            if (constructor == null) {
                // Class not found in the cache, see if it's real, and try to add it
                clazz = mContext.getClassLoader().loadClass(fqn)
                        .asSubclass(View.class);
                constructor = clazz.getConstructor(mConstructorSignature);
                constructor.setAccessible(true);
                sConstructorMap.put(name, constructor);
            }

            Object[] args = mConstructorArgs;
            args[1] = attrs;

            final View view = constructor.newInstance((Context) args[0]);

            if (view instanceof EditorView) {
                final ViewManagerImpl viewManager = new ViewManagerImpl(view);
                ((EditorView) view).setViewManager(viewManager);
            } else {
                throw new UnsupportedOperationException("TODO: Wrap unknown views");
            }
            return ((EditorView) view);
        } catch (NoSuchMethodException e) {
            throw new InflateException(
                    "Error inflating class " + (fqn), e);

        } catch (ClassCastException e) {
            // If loaded class is not a View subclass
            final InflateException ie = new InflateException(
                    "Class is not a View " + (fqn), e);
            ie.setStackTrace(EMPTY_STACK_TRACE);
            throw ie;
        } catch (ClassNotFoundException e) {
            // If loadClass fails, we should propagate the exception.
            throw e;
        } catch (Exception e) {
            final InflateException ie = new InflateException(
                    "Error inflating class " + (clazz == null ? "<unknown>" : clazz.getName()), e);
            ie.setStackTrace(EMPTY_STACK_TRACE);
            throw ie;
        }
    }

    @Nullable
    protected String replaceFqn(@NonNull String fqn) {
        return null;
    }

    private static final ClassLoader BOOT_CLASS_LOADER = EditorInflater.class.getClassLoader();

    private boolean verifyClassLoader(Constructor<? extends View> constructor) {
        final ClassLoader constructorLoader = constructor.getDeclaringClass().getClassLoader();
        if (constructorLoader == BOOT_CLASS_LOADER) {
            // fast path for boot class loader (most common case?) - always ok
            return true;
        }
        // in all normal cases (no dynamic code loading), we will exit the following loop on the
        // first iteration (i.e. when the declaring classloader is the contexts class loader).
        ClassLoader cl = mContext.getClassLoader();
        do {
            if (constructorLoader == cl) {
                return true;
            }
            cl = cl.getParent();
        } while (cl != null);
        return false;
    }
}
