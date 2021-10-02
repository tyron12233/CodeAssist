/*
 * Copyright 2019 Flipkart Internet Pvt. Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.flipkart.android.proteus.processor;

import android.content.Context;
import android.view.View;

import com.flipkart.android.proteus.DataContext;
import com.flipkart.android.proteus.FunctionManager;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.value.AttributeResource;
import com.flipkart.android.proteus.value.Binding;
import com.flipkart.android.proteus.value.NestedBinding;
import com.flipkart.android.proteus.value.ObjectValue;
import com.flipkart.android.proteus.value.Primitive;
import com.flipkart.android.proteus.value.Resource;
import com.flipkart.android.proteus.value.StyleResource;
import com.flipkart.android.proteus.value.Value;

import androidx.annotation.Nullable;

/**
 * @author kirankumar
 * @author adityasharat
 */
public abstract class AttributeProcessor<V extends View> {

  public static Value evaluate(final Context context, final Value input, final Value data, final int index) {
    final Value[] output = new Value[1];

    AttributeProcessor processor = new AttributeProcessor<View>() {

      @Override
      public void handleBinding(View view, Binding binding) {
        output[0] = binding.evaluate(context, data, index);
      }

      @Override
      public void handleValue(View view, Value value) {
        output[0] = value;
      }

      @Override
      public void handleResource(View view, Resource resource) {
        output[0] = new Primitive(resource.getString(context));
      }

      @Override
      public void handleAttributeResource(View view, AttributeResource attribute) {
        output[0] = new Primitive(attribute.apply(context).getString(0));
      }

      @Override
      public void handleStyleResource(View view, StyleResource style) {
        output[0] = new Primitive(style.apply(context).getString(0));
      }
    };

    //noinspection unchecked
    processor.process(null, input);

    return output[0];
  }

  @Nullable
  public static Value staticPreCompile(Primitive value, Context context, FunctionManager manager) {
    String string = value.getAsString();
    if (Binding.isBindingValue(string)) {
      return Binding.valueOf(string, context, manager);
    } else if (Resource.isResource(string)) {
      return Resource.valueOf(string, null, context);
    } else if (AttributeResource.isAttributeResource(string)) {
      return AttributeResource.valueOf(string, context);
    } else if (StyleResource.isStyleResource(string)) {
      return StyleResource.valueOf(string, context);
    }
    return null;
  }

  @Nullable
  public static Value staticPreCompile(ObjectValue object, Context context, FunctionManager manager) {
    Value binding = object.get(NestedBinding.NESTED_BINDING_KEY);
    if (null != binding) {
      return NestedBinding.valueOf(binding);
    }
    return null;
  }

  @Nullable
  public static Value staticPreCompile(Value value, Context context, FunctionManager manager) {
    Value compiled = null;
    if (value.isPrimitive()) {
      compiled = staticPreCompile(value.getAsPrimitive(), context, manager);
    } else if (value.isObject()) {
      compiled = staticPreCompile(value.getAsObject(), context, manager);
    } else if (value.isBinding() || value.isResource() || value.isAttributeResource() || value.isStyleResource()) {
      return value;
    }
    return compiled;
  }

  public void process(V view, Value value) {
    if (value.isBinding()) {
      handleBinding(view, value.getAsBinding());
    } else if (value.isResource()) {
      handleResource(view, value.getAsResource());
    } else if (value.isAttributeResource()) {
      handleAttributeResource(view, value.getAsAttributeResource());
    } else if (value.isStyleResource()) {
      handleStyleResource(view, value.getAsStyleResource());
    } else {
      handleValue(view, value);
    }
  }

  public void handleBinding(V view, Binding value) {
    DataContext dataContext = ((ProteusView) view).getViewManager().getDataContext();
    Value resolved = evaluate(value, view.getContext(), dataContext.getData(), dataContext.getIndex());
    handleValue(view, resolved);
  }

  public abstract void handleValue(V view, Value value);

  public abstract void handleResource(V view, Resource resource);

  public abstract void handleAttributeResource(V view, AttributeResource attribute);

  public abstract void handleStyleResource(V view, StyleResource style);

  public Value precompile(Value value, Context context, FunctionManager manager) {
    Value compiled = staticPreCompile(value, context, manager);
    return null != compiled ? compiled : compile(value, context);
  }

  public Value compile(@Nullable Value value, Context context) {
    return value;
  }

  protected Value evaluate(Binding binding, Context context, Value data, int index) {
    return binding.evaluate(context, data, index);
  }
}
