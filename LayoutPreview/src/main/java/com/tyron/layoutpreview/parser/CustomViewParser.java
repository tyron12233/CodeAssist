package com.tyron.layoutpreview.parser;

import android.graphics.Color;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.processor.StringAttributeProcessor;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.tyron.layoutpreview.model.Attribute;
import com.tyron.layoutpreview.model.CustomView;
import com.tyron.layoutpreview.view.CustomViewWrapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class CustomViewParser extends ViewTypeParser<View> {

    private CustomView mCustomView;

    public CustomViewParser(CustomView customView) {
        mCustomView = customView;
    }

    @NonNull
    @Override
    public String getType() {
        return mCustomView.getType();
    }

    @Nullable
    @Override
    public String getParentType() {
        return mCustomView.getParentType();
    }

    @NonNull
    @Override
    public ProteusView createView(@NonNull ProteusContext context, @NonNull Layout layout, @NonNull ObjectValue data, @Nullable ViewGroup parent, int dataIndex) {
        return new CustomViewWrapper(context, mCustomView);
    }

    @Override
    protected void addAttributeProcessors() {
        for (Attribute attribute : mCustomView.getAttributes()) {
            String name = attribute.getXmlName();
            String[] parameters = attribute.getParameters();
            String xmlParameter = parameters[attribute.getXmlParameterOffset()];
            Class<?>[] parametersClass = getParameters(parameters);


            if (xmlParameter.equals(String.class.getName())) {
                addAttributeProcessor(name, new StringAttributeProcessor<View>() {
                    @Override
                    public void setString(View view, String value) {
                       Method method = getMethod(view.getClass(), name, parametersClass);
                       if (method != null) {
                            Object[] objects = new Object[parameters.length];
                            objects[attribute.getXmlParameterOffset()] = value;

                            invokeValue(view, method, objects);
                       }
                    }
                });
            } else if (xmlParameter.equals(Integer.class.getName())) {
                addAttributeProcessor(name, new StringAttributeProcessor<View>() {
                    @Override
                    public void setString(View view, String string) {
                        int value = 0;
                        try {
                            value = Color.parseColor(string);
                        } catch (Exception e) {
                            value = Integer.parseInt(string);
                        }
                        Object[] objects = new Object[parameters.length];
                        objects[attribute.getXmlParameterOffset()] = value;
                        invokeValue(view, getMethod(view.getClass(), name, parametersClass), objects);
                    }
                });
            }
        }
    }

    private void invokeValue(Object object, Method method, Object[] values) {
        try {
            method.invoke(object, values);
        } catch (Exception e) {
            Log.w("CustomView", "Unable to set attribute " + Log.getStackTraceString(e));
        }
    }

    /**
     * Retrieves the method from the current class, if not found tries to find it in the parent class.
     *
     * @return null if method is not found
     */
    private Method getMethod(Class<? extends View> clazz, String name, Class<?>[] parameters) {
        Method method = null;
        try {
            method = clazz.getDeclaredMethod(name, parameters);
        } catch (Exception ignore) {

        }

        if (method == null) {
            try {
                method = clazz.getMethod(name, parameters);
            } catch (Exception ignore) {

            }
        }

        return method;
    }

    /**
     * Converts an array of fully qualified names to objects
     * @return null if an error has occurred
     */
    private Class<?>[] getParameters(String[] parameters) {
        Class<?>[] params = new Class<?>[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            if (int.class.getName().equals(parameters[i])) {
                params[i] = int.class;
                continue;
            }
            try {
                params[i] = Class.forName(parameters[i]);
            } catch (ClassNotFoundException e) {
                Log.w("CustomView", "Unable to find class " + parameters[i]);
                return null;
            }
        }
        return params;
    }
}
