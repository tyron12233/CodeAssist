package com.tyron.layoutpreview.parser;

import static com.tyron.layoutpreview.parser.WrapperUtils.addEnumProcessors;
import static com.tyron.layoutpreview.parser.WrapperUtils.addProcessors;
import static com.tyron.layoutpreview.parser.WrapperUtils.getMethod;
import static com.tyron.layoutpreview.parser.WrapperUtils.getParameters;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.tyron.layoutpreview.model.Attribute;
import com.tyron.layoutpreview.model.CustomView;
import com.tyron.layoutpreview.model.Format;
import com.tyron.layoutpreview.view.CustomViewGroupWrapper;
import com.flipkart.android.proteus.view.UnknownView;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class CustomViewGroupParser extends ViewTypeParser<ViewGroup> {

    private final CustomView mCustomView;

    public CustomViewGroupParser(CustomView customView) {
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
        View view = null;
        try {
            Class<?> clazz = Class.forName(mCustomView.getType());
            view = (View) clazz.getConstructor(Context.class)
                    .newInstance(context);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InstantiationException | InvocationTargetException e) {
            e.printStackTrace();
        }

        if (view == null) {
            return new UnknownView(context, "Unknown view: layout.type");
        }
        return new CustomViewGroupWrapper(context, (ViewGroup) view);
    }

    @Override
    protected void addAttributeProcessors() {
        Class<? extends View> clazz = null;
        try {
            clazz = Class.forName(mCustomView.getType())
                    .asSubclass(View.class);
        } catch (ClassNotFoundException e) {
            Log.w("CustomViewParser", "Unknown view class " + mCustomView.getType());
        }

        if (clazz == null) {
            return;
        }

        for (Attribute attribute : mCustomView.getAttributes()) {
            String methodName = attribute.getMethodName();
            String[] parameters = attribute.getParameters();
            Class<?>[] parametersClass = getParameters(parameters);
            Object[] objects = new Object[parameters.length];

            Method method = getMethod(clazz, methodName, parametersClass);

            if (attribute.getFormats().size() == 1 && !attribute.getFormats().contains(Format.ENUM)) {
                addProcessors(this, attribute, method, objects);
            } else if (attribute.getFormats().contains(Format.ENUM)) {
                addEnumProcessors(this, attribute, method, objects);
            }
        }
    }

}
