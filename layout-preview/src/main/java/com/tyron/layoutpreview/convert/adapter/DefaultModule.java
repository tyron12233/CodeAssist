package com.tyron.layoutpreview.convert.adapter;

import androidx.annotation.Nullable;

import com.flipkart.android.proteus.value.AttributeResource;
import com.flipkart.android.proteus.value.Binding;
import com.flipkart.android.proteus.value.Color;
import com.flipkart.android.proteus.value.Dimension;
import com.flipkart.android.proteus.value.DrawableValue;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.NestedBinding;
import com.flipkart.android.proteus.value.ObjectValue;
import com.flipkart.android.proteus.value.Resource;
import com.flipkart.android.proteus.value.StyleResource;
import com.flipkart.android.proteus.value.Value;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * DefaultModule
 *
 * @author adityasharat
 */
public class DefaultModule implements ProteusTypeAdapterFactory.Module {

    /**
     *
     */
    public final CustomValueTypeAdapterCreator<AttributeResource> ATTRIBUTE_RESOURCE = new CustomValueTypeAdapterCreator<AttributeResource>() {
        @Override
        public CustomValueTypeAdapter<AttributeResource> create(int type, ProteusTypeAdapterFactory factory) {
            return new CustomValueTypeAdapter<AttributeResource>(type) {
                @Override
                public void write(JsonWriter out, AttributeResource value) throws IOException {
                    out.value(value.attributeId);
                }

                @Override
                public AttributeResource read(JsonReader in) throws IOException {
                    return AttributeResource.valueOf(Integer.parseInt(in.nextString()));
                }
            };
        }
    };

    /**
     *
     */
    public final CustomValueTypeAdapterCreator<Binding> BINDING = new CustomValueTypeAdapterCreator<Binding>() {
        @Override
        public CustomValueTypeAdapter<Binding> create(int type, final ProteusTypeAdapterFactory factory) {
            return new CustomValueTypeAdapter<Binding>(type) {

                @Override
                public void write(JsonWriter out, Binding value) throws IOException {
                    out.value(value.toString());
                }

                @Override
                public Binding read(JsonReader in) throws IOException {
                    return Binding.valueOf(in.nextString(), factory.getContext(), ProteusTypeAdapterFactory.PROTEUS_INSTANCE_HOLDER.getProteus().functions);
                }
            };
        }
    };

    /**
     *
     */
    public final CustomValueTypeAdapterCreator<Color.Int> COLOR_INT = new CustomValueTypeAdapterCreator<Color.Int>() {
        @Override
        public CustomValueTypeAdapter<Color.Int> create(int type, ProteusTypeAdapterFactory factory) {
            return new CustomValueTypeAdapter<Color.Int>(type) {
                @Override
                public void write(JsonWriter out, Color.Int color) throws IOException {
                    out.value(color.value);
                }

                @Override
                public Color.Int read(JsonReader in) throws IOException {
                    return Color.Int.valueOf(Integer.parseInt(in.nextString()));
                }
            };
        }
    };

    /**
     *
     */
    public static final CustomValueTypeAdapterCreator<Color.StateList> COLOR_STATE_LIST = new CustomValueTypeAdapterCreator<Color.StateList>() {
        @Override
        public CustomValueTypeAdapter<Color.StateList> create(int type, ProteusTypeAdapterFactory factory) {
            return new CustomValueTypeAdapter<Color.StateList>(type) {

                private static final String KEY_STATES = "s";
                private static final String KEY_COLORS = "c";

                @Override
                public void write(JsonWriter out, Color.StateList value) throws IOException {
                    out.beginObject();

                    out.name(KEY_STATES);
                    out.value(ProteusTypeAdapterFactory.writeArrayOfIntArrays(value.states));

                    out.name(KEY_COLORS);
                    out.value(ProteusTypeAdapterFactory.writeArrayOfInts(value.colors));

                    out.endObject();
                }

                @Override
                public Color.StateList read(JsonReader in) throws IOException {
                    in.beginObject();

                    in.nextName();
                    int[][] states = ProteusTypeAdapterFactory.readArrayOfIntArrays(in.nextString());

                    in.nextName();
                    int colors[] = ProteusTypeAdapterFactory.readArrayOfInts(in.nextString());

                    in.endObject();

                    return Color.StateList.valueOf(states, colors);
                }
            };
        }
    };

    /**
     *
     */
    public final CustomValueTypeAdapterCreator<Dimension> DIMENSION = new CustomValueTypeAdapterCreator<Dimension>() {
        @Override
        public CustomValueTypeAdapter<Dimension> create(int type, ProteusTypeAdapterFactory factory) {
            return new CustomValueTypeAdapter<Dimension>(type) {
                @Override
                public void write(JsonWriter out, Dimension value) throws IOException {
                    out.value(value.toString());
                }

                @Override
                public Dimension read(JsonReader in) throws IOException {
                    return Dimension.valueOf(in.nextString());
                }
            };
        }
    };

    /**
     *
     */
    public final CustomValueTypeAdapterCreator<DrawableValue.ColorValue> DRAWABLE_COLOR = new CustomValueTypeAdapterCreator<DrawableValue.ColorValue>() {
        @Override
        public CustomValueTypeAdapter<DrawableValue.ColorValue> create(int type, final ProteusTypeAdapterFactory factory) {
            return new CustomValueTypeAdapter<DrawableValue.ColorValue>(type) {
                @Override
                public void write(JsonWriter out, DrawableValue.ColorValue value) throws IOException {
                    factory.COMPILED_VALUE_TYPE_ADAPTER.write(out, value.color);
                }

                @Override
                public DrawableValue.ColorValue read(JsonReader in) throws IOException {
                    return DrawableValue.ColorValue.valueOf(factory.COMPILED_VALUE_TYPE_ADAPTER.read(in), factory.getContext());
                }
            };
        }
    };

    /**
     *
     */
    public final CustomValueTypeAdapterCreator<DrawableValue.LayerListValue> DRAWABLE_LAYER_LIST = new CustomValueTypeAdapterCreator<DrawableValue.LayerListValue>() {
        @Override
        public CustomValueTypeAdapter<DrawableValue.LayerListValue> create(int type, final ProteusTypeAdapterFactory factory) {
            return new CustomValueTypeAdapter<DrawableValue.LayerListValue>(type) {

                private static final String KEY_IDS = "i";
                private static final String KEY_LAYERS = "l";

                @Override
                public void write(JsonWriter out, DrawableValue.LayerListValue value) throws IOException {

                    out.beginObject();

                    out.name(KEY_IDS);
                    Iterator<Integer> i = value.getIds();
                    out.beginArray();
                    while (i.hasNext()) {
                        out.value(i.next());
                    }
                    out.endArray();

                    out.name(KEY_LAYERS);
                    Iterator<Value> l = value.getLayers();
                    out.beginArray();
                    while (l.hasNext()) {
                        factory.COMPILED_VALUE_TYPE_ADAPTER.write(out, l.next());
                    }
                    out.endArray();

                    out.endObject();
                }

                @Override
                public DrawableValue.LayerListValue read(JsonReader in) throws IOException {

                    in.beginObject();

                    in.nextName();
                    int[] ids = new int[0];
                    in.beginArray();
                    while (in.hasNext()) {
                        ids = Arrays.copyOf(ids, ids.length + 1);
                        ids[ids.length - 1] = Integer.parseInt(in.nextString());
                    }
                    in.endArray();

                    in.nextName();
                    Value[] layers = new Value[0];
                    in.beginArray();
                    while (in.hasNext()) {
                        layers = Arrays.copyOf(layers, layers.length + 1);
                        layers[layers.length - 1] = factory.COMPILED_VALUE_TYPE_ADAPTER.read(in);
                    }
                    in.endArray();

                    in.endObject();

                    return DrawableValue.LayerListValue.valueOf(ids, layers);
                }
            };
        }
    };

    /**
     *
     */
    public final CustomValueTypeAdapterCreator<DrawableValue.LevelListValue> DRAWABLE_LEVEL_LIST = new CustomValueTypeAdapterCreator<DrawableValue.LevelListValue>() {
        @Override
        public CustomValueTypeAdapter<DrawableValue.LevelListValue> create(int type, final ProteusTypeAdapterFactory factory) {
            return new CustomValueTypeAdapter<DrawableValue.LevelListValue>(type) {

                private static final String KEY_MIN_LEVEL = "i";
                private static final String KEY_MAX_LEVEL = "a";
                private static final String KEY_DRAWABLE = "d";

                @Override
                public void write(JsonWriter out, DrawableValue.LevelListValue value) throws IOException {
                    out.beginArray();
                    Iterator<DrawableValue.LevelListValue.Level> iterator = value.getLevels();
                    DrawableValue.LevelListValue.Level level;
                    while (iterator.hasNext()) {
                        level = iterator.next();

                        out.beginObject();

                        out.name(KEY_MIN_LEVEL);
                        out.value(level.minLevel);

                        out.name(KEY_MAX_LEVEL);
                        out.value(level.maxLevel);

                        out.name(KEY_DRAWABLE);
                        factory.COMPILED_VALUE_TYPE_ADAPTER.write(out, level.drawable);

                        out.endObject();
                    }
                    out.endArray();
                }

                @Override
                public DrawableValue.LevelListValue read(JsonReader in) throws IOException {
                    DrawableValue.LevelListValue.Level[] levels = new DrawableValue.LevelListValue.Level[0];

                    in.beginArray();
                    int minLevel, maxLevel;
                    Value drawable;
                    DrawableValue.LevelListValue.Level level;

                    while (in.hasNext()) {
                        in.beginObject();

                        in.nextName();
                        minLevel = Integer.parseInt(in.nextString());

                        in.nextName();
                        maxLevel = Integer.parseInt(in.nextString());

                        in.nextName();
                        drawable = factory.COMPILED_VALUE_TYPE_ADAPTER.read(in);

                        level = DrawableValue.LevelListValue.Level.valueOf(minLevel, maxLevel, drawable, factory.getContext());

                        levels = Arrays.copyOf(levels, levels.length + 1);
                        levels[levels.length - 1] = level;

                        in.endObject();
                    }

                    in.endArray();

                    return DrawableValue.LevelListValue.value(levels);
                }
            };
        }
    };

    /**
     *
     */
    public final CustomValueTypeAdapterCreator<DrawableValue.ShapeValue> DRAWABLE_SHAPE = new CustomValueTypeAdapterCreator<DrawableValue.ShapeValue>() {
        @Override
        public CustomValueTypeAdapter<DrawableValue.ShapeValue> create(int type, final ProteusTypeAdapterFactory factory) {
            return new CustomValueTypeAdapter<DrawableValue.ShapeValue>(type) {
                @Override
                public void write(JsonWriter out, DrawableValue.ShapeValue value) throws IOException {
                    // TODO: remove mock
                    out.value("#00000000");
                }

                @Override
                public DrawableValue.ShapeValue read(JsonReader in) throws IOException {
                    // TODO: remove mock
                    in.skipValue();
                    return DrawableValue.ShapeValue.valueOf(0, null, null);
                }
            };
        }
    };

    /**
     *
     */
    public final CustomValueTypeAdapterCreator<DrawableValue.RippleValue> DRAWABLE_RIPPLE = new CustomValueTypeAdapterCreator<DrawableValue.RippleValue>() {
        @Override
        public CustomValueTypeAdapter<DrawableValue.RippleValue> create(int type, final ProteusTypeAdapterFactory factory) {
            return new CustomValueTypeAdapter<DrawableValue.RippleValue>(type) {

                private static final String KEY_COLOR = "c";
                private static final String KEY_MASK = "m";
                private static final String KEY_CONTENT = "t";
                private static final String KEY_DEFAULT_BACKGROUND = "d";

                @Override
                public void write(JsonWriter out, DrawableValue.RippleValue value) throws IOException {
                    out.beginObject();

                    out.name(KEY_COLOR);
                    factory.COMPILED_VALUE_TYPE_ADAPTER.write(out, value.color);

                    if (value.mask != null) {
                        out.name(KEY_MASK);
                        factory.COMPILED_VALUE_TYPE_ADAPTER.write(out, value.mask);
                    }

                    if (value.content != null) {
                        out.name(KEY_CONTENT);
                        factory.COMPILED_VALUE_TYPE_ADAPTER.write(out, value.content);
                    }

                    if (value.defaultBackground != null) {
                        out.name(KEY_DEFAULT_BACKGROUND);
                        factory.COMPILED_VALUE_TYPE_ADAPTER.write(out, value.defaultBackground);
                    }

                    out.endObject();
                }

                @Override
                public DrawableValue.RippleValue read(JsonReader in) throws IOException {

                    in.beginObject();

                    String name;
                    Value color = null, mask = null, content = null, defaultBackground = null;

                    while (in.hasNext()) {
                        name = in.nextName();
                        switch (name) {
                            case KEY_COLOR:
                                color = factory.COMPILED_VALUE_TYPE_ADAPTER.read(in);
                                break;
                            case KEY_MASK:
                                mask = factory.COMPILED_VALUE_TYPE_ADAPTER.read(in);
                                break;
                            case KEY_CONTENT:
                                content = factory.COMPILED_VALUE_TYPE_ADAPTER.read(in);
                                break;
                            case KEY_DEFAULT_BACKGROUND:
                                defaultBackground = factory.COMPILED_VALUE_TYPE_ADAPTER.read(in);
                                break;
                            default:
                                throw new IllegalStateException("Bad attribute '" + name + "'");
                        }
                    }

                    in.endObject();

                    if (color == null) {
                        throw new IllegalStateException("color is a required attribute in Ripple Drawable");
                    }

                    return DrawableValue.RippleValue.valueOf(color, mask, content, defaultBackground);
                }
            };
        }
    };

    /**
     *
     */
    public final CustomValueTypeAdapterCreator<DrawableValue.StateListValue> DRAWABLE_STATE_LIST = new CustomValueTypeAdapterCreator<DrawableValue.StateListValue>() {
        @Override
        public CustomValueTypeAdapter<DrawableValue.StateListValue> create(int type, final ProteusTypeAdapterFactory factory) {
            return new CustomValueTypeAdapter<DrawableValue.StateListValue>(type) {

                private static final String KEY_STATES = "s";
                private static final String KEY_VALUES = "v";

                @Override
                public void write(JsonWriter out, DrawableValue.StateListValue value) throws IOException {
                    out.beginObject();

                    out.name(KEY_STATES);
                    out.value(ProteusTypeAdapterFactory.writeArrayOfIntArrays(value.states));

                    out.name(KEY_VALUES);
                    out.beginArray();
                    Iterator<Value> iterator = value.getValues();
                    while (iterator.hasNext()) {
                        factory.COMPILED_VALUE_TYPE_ADAPTER.write(out, iterator.next());
                    }
                    out.endArray();

                    out.endObject();
                }

                @Override
                public DrawableValue.StateListValue read(JsonReader in) throws IOException {

                    in.beginObject();

                    in.nextName();
                    int[][] states = ProteusTypeAdapterFactory.readArrayOfIntArrays(in.nextString());

                    in.nextName();
                    Value[] values = new Value[0];

                    in.beginArray();
                    while (in.hasNext()) {
                        values = Arrays.copyOf(values, values.length + 1);
                        values[values.length - 1] = factory.COMPILED_VALUE_TYPE_ADAPTER.read(in);
                    }
                    in.endArray();

                    in.endObject();

                    return DrawableValue.StateListValue.valueOf(states, values);
                }
            };
        }
    };

    /**
     *
     */
    public final CustomValueTypeAdapterCreator<DrawableValue.UrlValue> DRAWABLE_URL = new CustomValueTypeAdapterCreator<DrawableValue.UrlValue>() {
        @Override
        public CustomValueTypeAdapter<DrawableValue.UrlValue> create(int type, ProteusTypeAdapterFactory factory) {
            return new CustomValueTypeAdapter<DrawableValue.UrlValue>(type) {
                @Override
                public void write(JsonWriter out, DrawableValue.UrlValue value) throws IOException {
                    out.value(value.url);
                }

                @Override
                public DrawableValue.UrlValue read(JsonReader in) throws IOException {
                    return DrawableValue.UrlValue.valueOf(in.nextString());
                }
            };
        }
    };

    /**
     *
     */
    public final CustomValueTypeAdapterCreator<Layout> LAYOUT = new CustomValueTypeAdapterCreator<Layout>() {
        @Override
        public CustomValueTypeAdapter<Layout> create(int type, final ProteusTypeAdapterFactory factory) {
            return new CustomValueTypeAdapter<Layout>(type) {

                private static final String KEY_TYPE = "t";
                private static final String KEY_DATA = "d";
                private static final String KEY_ATTRIBUTES = "a";
                private static final String KEY_EXTRAS = "e";
                private static final String KEY_ATTRIBUTE_ID = "i";
                private static final String KEY_ATTRIBUTE_VALUE = "v";

                @Override
                public void write(JsonWriter out, Layout value) throws IOException {
                    out.beginObject();

                    out.name(KEY_TYPE);
                    out.value(value.type);

                    if (null != value.data) {
                        out.name(KEY_DATA);
                        out.beginObject();
                        for (Map.Entry<String, Value> entry : value.data.entrySet()) {
                            out.name(entry.getKey());
                            factory.COMPILED_VALUE_TYPE_ADAPTER.write(out, entry.getValue());
                        }
                        out.endObject();
                    }


                    if (null != value.attributes) {
                        out.name(KEY_ATTRIBUTES);
                        out.beginArray();
                        for (Layout.Attribute attribute : value.attributes) {
                            out.beginObject();

                            out.name(KEY_ATTRIBUTE_ID);
                            out.value(attribute.id);

                            out.name(KEY_ATTRIBUTE_VALUE);
                            factory.COMPILED_VALUE_TYPE_ADAPTER.write(out, attribute.value);

                            out.endObject();
                        }
                        out.endArray();
                    }

                    if (null != value.extras) {
                        out.name(KEY_EXTRAS);
                        factory.COMPILED_VALUE_TYPE_ADAPTER.write(out, value.extras);
                    }

                    out.endObject();
                }

                @Override
                public Layout read(JsonReader in) throws IOException {
                    in.beginObject();

                    String name;
                    String type = null;
                    Map<String, Value> data = null;
                    List<Layout.Attribute> attributes = null;
                    ObjectValue extras = null;

                    while (in.hasNext()) {
                        name = in.nextName();
                        switch (name) {
                            case KEY_TYPE:
                                type = in.nextString();
                                break;
                            case KEY_DATA:
                                data = readData(in);
                                break;
                            case KEY_ATTRIBUTES:
                                attributes = readAttributes(in);
                                break;
                            case KEY_EXTRAS:
                                extras = readExtras(in);
                                break;
                            default:
                                throw new IllegalStateException("Bad attribute '" + name + "'");
                        }
                    }

                    in.endObject();

                    if (null == type) {
                        throw new IllegalStateException("Layout must have type attribute!");
                    }

                    //noinspection ConstantConditions
                    return new Layout(type, attributes, data, extras);
                }

                @Nullable
                private Map<String, Value> readData(JsonReader in) throws IOException {
                    Map<String, Value> data = new HashMap<>();
                    in.beginObject();
                    String name;
                    Value value;
                    while (in.hasNext()) {
                        name = in.nextName();
                        value = factory.COMPILED_VALUE_TYPE_ADAPTER.read(in);
                        data.put(name, value);
                    }
                    in.endObject();
                    return data;
                }

                @Nullable
                private ObjectValue readExtras(JsonReader in) throws IOException {
                    return factory.COMPILED_VALUE_TYPE_ADAPTER.read(in).getAsObject();
                }

                @Nullable
                private List<Layout.Attribute> readAttributes(JsonReader in) throws IOException {
                    List<Layout.Attribute> attributes = new ArrayList<>();

                    in.beginArray();
                    int id;
                    Value value;
                    while (in.hasNext()) {
                        in.beginObject();
                        in.nextName();
                        id = Integer.parseInt(in.nextString());
                        in.nextName();
                        value = factory.COMPILED_VALUE_TYPE_ADAPTER.read(in);
                        attributes.add(new Layout.Attribute(id, value));
                        in.endObject();
                    }
                    in.endArray();

                    return attributes;
                }
            };
        }
    };

    /**
     *
     */
    public final CustomValueTypeAdapterCreator<NestedBinding> NESTED_BINDING = new CustomValueTypeAdapterCreator<NestedBinding>() {
        @Override
        public CustomValueTypeAdapter<NestedBinding> create(int type, final ProteusTypeAdapterFactory factory) {
            return new CustomValueTypeAdapter<NestedBinding>(type) {
                @Override
                public void write(JsonWriter out, NestedBinding value) throws IOException {
                    factory.COMPILED_VALUE_TYPE_ADAPTER.write(out, value.getValue());
                }

                @Override
                public NestedBinding read(JsonReader in) throws IOException {
                    return NestedBinding.valueOf(factory.COMPILED_VALUE_TYPE_ADAPTER.read(in));
                }
            };
        }
    };

    /**
     *
     */
    public final CustomValueTypeAdapterCreator<Resource> RESOURCE = new CustomValueTypeAdapterCreator<Resource>() {
        @Override
        public CustomValueTypeAdapter<Resource> create(int type, ProteusTypeAdapterFactory factory) {
            return new CustomValueTypeAdapter<Resource>(type) {
                @Override
                public void write(JsonWriter out, Resource value) throws IOException {
                    out.value(value.resId);
                }

                @Override
                public Resource read(JsonReader in) throws IOException {
                    return Resource.valueOf(Integer.parseInt(in.nextString()));
                }
            };
        }
    };

    /**
     *
     */
    public final CustomValueTypeAdapterCreator<StyleResource> STYLE_RESOURCE = new CustomValueTypeAdapterCreator<StyleResource>() {
        @Override
        public CustomValueTypeAdapter<StyleResource> create(int type, ProteusTypeAdapterFactory factory) {
            return new CustomValueTypeAdapter<StyleResource>(type) {

                private static final String KEY_ATTRIBUTE_ID = "a";
                private static final String KEY_STYLE_ID = "s";

                @Override
                public void write(JsonWriter out, StyleResource value) throws IOException {
                    out.beginObject();

                    out.name(KEY_ATTRIBUTE_ID);
                    out.value(value.attributeId);

                    out.name(KEY_STYLE_ID);
                    out.value(value.styleId);

                    out.endObject();
                }

                @Override
                public StyleResource read(JsonReader in) throws IOException {
                    in.beginObject();
                    in.nextName();
                    String attributeId = in.nextString();
                    in.nextName();
                    String styleId = in.nextString();
                    in.endObject();
                    return StyleResource.valueOf(Integer.parseInt(styleId), Integer.parseInt(attributeId));
                }
            };
        }
    };

    private DefaultModule() {
    }

    public static DefaultModule create() {
        return new DefaultModule();
    }

    @Override
    public void register(ProteusTypeAdapterFactory factory) {
        factory.register(AttributeResource.class, ATTRIBUTE_RESOURCE);
        factory.register(Binding.class, BINDING);
        factory.register(Color.Int.class, COLOR_INT);
        factory.register(Color.StateList.class, COLOR_STATE_LIST);
        factory.register(Dimension.class, DIMENSION);

        /*factory.register(DrawableValue.Gradient.class, DRAWABLE_VALUE);
        factory.register(DrawableValue.Corners.class, DRAWABLE_VALUE);
        factory.register(DrawableValue.Solid.class, DRAWABLE_VALUE);
        factory.register(DrawableValue.Size.class, DRAWABLE_VALUE);
        factory.register(DrawableValue.Stroke.class, DRAWABLE_VALUE);*/

        factory.register(DrawableValue.ColorValue.class, DRAWABLE_COLOR);
        factory.register(DrawableValue.LayerListValue.class, DRAWABLE_LAYER_LIST);
        factory.register(DrawableValue.LevelListValue.class, DRAWABLE_LEVEL_LIST);
        factory.register(DrawableValue.RippleValue.class, DRAWABLE_RIPPLE);
        factory.register(DrawableValue.ShapeValue.class, DRAWABLE_SHAPE);
        factory.register(DrawableValue.StateListValue.class, DRAWABLE_STATE_LIST);
        factory.register(DrawableValue.UrlValue.class, DRAWABLE_URL);

        factory.register(Layout.class, LAYOUT);
        factory.register(NestedBinding.class, NESTED_BINDING);
        factory.register(Resource.class, RESOURCE);
        factory.register(StyleResource.class, STYLE_RESOURCE);
    }

}