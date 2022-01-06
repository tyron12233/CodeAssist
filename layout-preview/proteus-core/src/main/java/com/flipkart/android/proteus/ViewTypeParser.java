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

package com.flipkart.android.proteus;

import android.content.res.XmlResourceParser;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.flipkart.android.proteus.managers.ViewManager;
import com.flipkart.android.proteus.processor.AttributeProcessor;
import com.flipkart.android.proteus.toolbox.ProteusHelper;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.flipkart.android.proteus.value.Value;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * @author adityasharat
 */
@SuppressWarnings("JavaDoc")
public abstract class ViewTypeParser<V extends View> {

  private static XmlResourceParser sParser = null;

  @Nullable
  public ViewTypeParser<V> parent;

  @SuppressWarnings({"rawtypes"})
  private AttributeProcessor[] processors = new AttributeProcessor[0];

  private Map<String, AttributeSet.Attribute> attributes = new HashMap<>();

  private int offset = 0;

  private AttributeSet attributeSet;

  /**
   * @return
   */
  @NonNull
  public abstract String getType();

  /**
   * @return
   */
  @Nullable
  public abstract String getParentType();

  @Nullable
  protected String getDefaultStyleName() {
    return null;
  }

  /**
   * @param context
   * @param layout
   * @param data
   * @param parent
   * @param dataIndex
   * @return
   */
  @NonNull
  public abstract ProteusView createView(@NonNull ProteusContext context, @NonNull Layout layout,
                                         @NonNull ObjectValue data, @Nullable ViewGroup parent, int dataIndex);

  /**
   * @param context
   * @param view
   * @param layout
   * @param data
   * @param caller
   * @param parent
   * @param dataIndex @return
   */
  @NonNull
  public ProteusView.Manager createViewManager(@NonNull ProteusContext context, @NonNull ProteusView view,
                                               @NonNull Layout layout, @NonNull ObjectValue data,
                                               @Nullable ViewTypeParser<V> caller, @Nullable ViewGroup parent,
                                               int dataIndex) {
    if (null != this.parent && caller != this.parent) {
      return this.parent.createViewManager(context, view, layout, data, caller, parent, dataIndex);
    } else {
      DataContext dataContext = createDataContext(context, layout, data, parent, dataIndex);
      return new ViewManager(context, caller != null ? caller : this, view.getAsView(), layout, dataContext);
    }
  }

  /**
   * @param context
   * @param layout
   * @param data
   * @param parent
   * @param dataIndex
   * @return
   */
  @NonNull
  protected DataContext createDataContext(ProteusContext context, @NonNull Layout layout, @NonNull ObjectValue data,
                                          @Nullable ViewGroup parent, int dataIndex) {
    DataContext dataContext, parentDataContext = null;
    Map<String, Value> map = layout.data;

    if (parent instanceof ProteusView) {
      parentDataContext = ((ProteusView) parent).getViewManager().getDataContext();
    }

    if (map == null) {
      if (parentDataContext == null) {
        dataContext = DataContext.create(context, data, dataIndex);
      } else {
        dataContext = parentDataContext.copy();
      }
    } else {
      if (parentDataContext == null) {
        dataContext = DataContext.create(context, data, dataIndex, map);
      } else {
        dataContext = parentDataContext.createChild(context, map, dataIndex);
      }
    }
    return dataContext;
  }

  /**
   * @param view
   * @param parent
   * @param dataIndex
   */
  public void onAfterCreateView(@NonNull ProteusView view, @Nullable ViewGroup parent, int dataIndex) {
    View v = view.getAsView();
    if (null == v.getLayoutParams()) {
      ViewGroup.LayoutParams layoutParams;
      if (parent != null) {
        layoutParams = generateDefaultLayoutParams(parent);
      } else {
        layoutParams = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
      }
      v.setLayoutParams(layoutParams);
    }
  }

  /**
   *
   */
  protected abstract void addAttributeProcessors();

  /**
   *
   * @param parent
   * @param view
   * @param attributeId
   * @param value
   * @return
   */
  public boolean handleAttribute(View parent, V view, int attributeId, Value value) {
    int position = getPosition(attributeId);
    if (position < 0) {
      return null != this.parent && this.parent.handleAttribute(parent, view, attributeId, value);
    }
    try {
      //noinspection unchecked
      AttributeProcessor<V> processor = processors[position];
      processor.process(parent, view, value);
      return true;
    } catch (Throwable e) {
      String name = "Unknown attribute";
      String attributeValue = String.valueOf(value);
      if (view instanceof ProteusView) {
        name = ProteusHelper.getAttributeName(((ProteusView) view), attributeId);
      }
      Log.e("ViewTypeParser", "Unable to handle attribute: " + name + " value: " + attributeValue, e);
      return false;
    }
  }

  /**
   * @param view
   * @param children
   * @return
   */
  public boolean handleChildren(V view, Value children) {
    return null != parent && parent.handleChildren(view, children);
  }

  /**
   * @param parent
   * @param view
   * @return
   */
  public boolean addView(ProteusView parent, ProteusView view) {
    return null != this.parent && this.parent.addView(parent, view);
  }

  /**
   * @param parent
   * @param extras
   * @return
   */
  @NonNull
  public AttributeSet prepare(@Nullable ViewTypeParser<V> parent, @Nullable Map<String, AttributeProcessor<V>> extras) {
    this.parent = parent;
    this.processors = new AttributeProcessor[0];
    this.attributes = new HashMap<>();
    this.offset = null != parent ? parent.getAttributeSet().getOffset() : 0;

    addAttributeProcessors();

    if (extras != null) {
      addAttributeProcessors(extras);
    }

    this.attributeSet = new AttributeSet(attributes.size() > 0 ? attributes : null, null != parent ? parent.getAttributeSet() : null, processors.length);
    return attributeSet;
  }

  /**
   * @param name
   * @return
   */
  public int getAttributeId(String name) {
    AttributeSet.Attribute attribute = attributeSet.getAttribute(name);
    return null != attribute ? attribute.id : -1;
  }

  /**
   * @return
   */
  @NonNull
  public AttributeSet getAttributeSet() {
    return this.attributeSet;
  }

  protected void addAttributeProcessors(@NonNull Map<String, AttributeProcessor<V>> processors) {
    for (Map.Entry<String, AttributeProcessor<V>> entry : processors.entrySet()) {
      addAttributeProcessor(entry.getKey(), entry.getValue());
    }
  }

  public void addLayoutParamsAttributeProcessor(String name, AttributeProcessor<V> processor) {
    addAttributeProcessor(processor);
    attributes.put(name,
            new AttributeSet.Attribute(getAttributeId(processors.length - 1),
                    processor,
                    true));
  }

  /**
   * @param name
   * @param processor
   */
  public void addAttributeProcessor(String name, AttributeProcessor<V> processor) {
    addAttributeProcessor(processor);
    attributes.put(name, new AttributeSet.Attribute(getAttributeId(processors.length - 1), processor));
  }

  private void addAttributeProcessor(AttributeProcessor<V> handler) {
    processors = Arrays.copyOf(processors, processors.length + 1);
    processors[processors.length - 1] = handler;
  }

  private int getOffset() {
    return offset;
  }

  private int getPosition(int attributeId) {
    return attributeId + getOffset();
  }

  private int getAttributeId(int position) {
    return position - getOffset();
  }

  @SuppressWarnings("DanglingJavadoc")
  private ViewGroup.LayoutParams generateDefaultLayoutParams(@NonNull ViewGroup parent) {

    /**
     * This whole method is a hack! To generate layout params, since no other way exists.
     * Refer : http://stackoverflow.com/questions/7018267/generating-a-layoutparams-based-on-the-type-of-parent
     */
    if (null == sParser) {
      synchronized (ViewTypeParser.class) {
        if (null == sParser) {
          initializeAttributeSet(parent);
        }
      }
    }

    return parent.generateLayoutParams(sParser);
  }

  private void initializeAttributeSet(@NonNull ViewGroup parent) {
    sParser = parent.getResources().getLayout(R.layout.layout_params_hack);
    try {
      //noinspection StatementWithEmptyBody
      while (sParser.nextToken() != XmlPullParser.START_TAG) {
        // Skip everything until the view tag.
      }
    } catch (XmlPullParserException | IOException e) {
      e.printStackTrace();
    }
  }

  /**
   * @author adityasharat
   */
  public static class AttributeSet {

    @NonNull
    private final Map<String, Attribute> attributes;

    @Nullable
    private final AttributeSet parent;

    private final int offset;

    AttributeSet(@Nullable Map<String, Attribute> attributes, @Nullable AttributeSet parent, int offset) {
      this.attributes = null != attributes ? attributes : new HashMap<>();
      this.parent = parent;
      int parentOffset = null != parent ? parent.getOffset() : 0;
      this.offset = parentOffset - offset;
    }

    @Nullable
    public Attribute getAttribute(String name) {
      Attribute attribute = attributes.get(name);
      if (null != attribute) {
        return attribute;
      } else if (null != parent) {
        return parent.getAttribute(name);
      } else {
        return null;
      }
    }

    public Map<String, Attribute> getLayoutParamsAttributes() {
      Map<String, Attribute> attributeMap = new HashMap<>();

      for (Map.Entry<String, Attribute> attr : attributes.entrySet()) {
        if (attr.getValue().isLayoutParams) {
          attributeMap.put(attr.getKey(), attr.getValue());
        }
      }
      if (parent != null) {
        attributeMap.putAll(parent.getLayoutParamsAttributes());
      }
      return attributeMap;
    }

    public Map<String, Attribute> getAttributes() {
      Map<String, Attribute> attributeMap = new HashMap<>();

      for (Map.Entry<String, Attribute> attr : attributes.entrySet()) {
        if (!attr.getValue().isLayoutParams) {
          attributeMap.put(attr.getKey(), attr.getValue());
        }
      }
      if (parent != null) {
        attributeMap.putAll(parent.getAttributes());
      }
      return attributeMap;
    }

    int getOffset() {
      return offset;
    }

    public static class Attribute {

      public final boolean isLayoutParams;
      public final int id;

      @NonNull
      public final AttributeProcessor<?> processor;

      Attribute(int id, @NonNull AttributeProcessor<?> processor) {
        this(id, processor, false);
      }

      Attribute(int id, @NonNull AttributeProcessor<?> processor, boolean isLayoutParams) {
        this.processor = processor;
        this.id = id;
        this.isLayoutParams = isLayoutParams;
      }
    }
  }

}
