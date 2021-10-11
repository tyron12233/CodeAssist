package com.tyron.layoutpreview.model;


import android.view.View;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.tyron.layoutpreview.parser.WrapperUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class Attribute {

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Convenience method to create an attribute class from a JSON String
     * @param json Input JSON
     * @return Attribute class
     * @throws JsonSyntaxException if JSON input is malformed
     */
    public static Attribute fromJson(String json) throws JsonSyntaxException {
        return new Gson().fromJson(json, Attribute.class);
    }

    /**
     * The method name that will be used to invoke this method
     * eg. {@code setText}
     */
    private String methodName;

    /**
     * The name of this attribute used in xml including the custom namespace (Don't include for android namespace)
     * eg. {@code app:layout_constraintLeft_toLeftOf}
     */
    private String xmlName;

    /**
     * The fully qualified class names of the parameters needed to invoke the method
     * eg. {@code java.lang.String, java.lang.Integer}
     */
    private String[] parameters;

    /**
     * The parameter offset in the parameters to which the xml attribute will be applied
     * since you can only define one parameter in xml
     *
     * eg. a method that contains more than one parameter like {@code setText(String, Int)}
     * we can only get one parameter value in xml thus we need to specify which one it corresponds to
     */
    private int xmlParameterOffset;

    /**
     * Use {@link Format} instead
     */
    @Deprecated
    private boolean isDimension;

    private String layoutParamsClass;

    /**
     * Determines whether this attribute should be applied on a LayoutParams class or not
     */
    private boolean isLayoutParams;

    private List<Format> formats;

    private Map<String, Integer> enumValues;

    public Attribute() {

    }

    public String getMethodName() {
        return methodName;
    }

    public String[] getParameters() {
        return parameters;
    }

    public void setMethodName(String name) {
        methodName = name;
    }

    public void setParameters(String[] parameters) {
        this.parameters = parameters;
    }

    public String getXmlName() {
        return xmlName;
    }

    public void setXmlName(String xmlName) {
        this.xmlName = xmlName;
    }

    public int getXmlParameterOffset() {
        return xmlParameterOffset;
    }

    public void setXmlParameterOffset(int xmlParameterOffset) {
        this.xmlParameterOffset = xmlParameterOffset;
    }

    public boolean isDimension() {
        return isDimension;
    }

    public void setDimension(boolean dimension) {
        isDimension = dimension;
    }

    public boolean isLayoutParams() {
        return isLayoutParams;
    }

    public void setLayoutParams(boolean layoutParams) {
        isLayoutParams = layoutParams;
    }

    public String getLayoutParamsClass() {
        return layoutParamsClass;
    }

    public void setLayoutParamsClass(String layoutParamsClass) {
        this.layoutParamsClass = layoutParamsClass;
    }

    public Map<String, Integer> getEnumValues() {
        return enumValues;
    }

    public void setEnumValues(Map<String, Integer> enumValues) {
        this.enumValues = enumValues;
    }

    public List<Format> getFormats() {
        return formats;
    }

    public void setFormats(List<Format> formats) {
        this.formats = formats;
    }

    /**
     * Apply this attribute to the given view
     */
    public void apply(View view, Object[] values) {
        WrapperUtils.set(this, view, WrapperUtils.getParameters(parameters), values);
    }

    /**
     * Builder class to help constructing an Attribute
     */
    public static class Builder {

        private String xmlName;
        private String[] parameters;
        private int xmlOffset;
        private String methodName;
        private boolean isDimension;
        private String layoutParamsClass;
        private boolean isLayoutParams;
        private final List<Format> formats = new ArrayList<>();
        private Map<String, Integer> enumValues;

        /**
         * use {@link Attribute#builder()}
         */
        private Builder() {

        }

        public Builder setXmlName(String xmlName) {
            this.xmlName = xmlName;
            return this;
        }

        public Builder setParameters(String... params) {
            this.parameters = params;
            if (parameters.length == 1) {
                xmlOffset = 0;
            }
            return this;
        }

        public Builder setParameters(Class<?>... params) {
            if (params.length == 1) {
                xmlOffset = 0;
            }
            this.parameters = Arrays.stream(params)
                    .map(Class::getName)
                    .toArray(String[]::new);
            return this;
        }

        public Builder setMethodName(String name) {
            this.methodName = name;
            return this;
        }

        public Builder setDimension(boolean value) {
            this.isDimension = value;
            return this;
        }

        public Builder setLayoutParamsClass(String name) {
            this.layoutParamsClass = name;
            this.isLayoutParams = true;
            return this;
        }

        public Builder setLayoutParams(boolean value) {
            this.isLayoutParams = value;
            return this;
        }

        public Builder addFormat(Format format) {
            formats.add(format);
            return this;
        }

        public Builder setEnumValues(Map<String, Integer> values) {
            enumValues = values;
            return this;
        }

        public Attribute build() {
            Attribute attribute = new Attribute();
            attribute.setXmlParameterOffset(xmlOffset);
            attribute.setParameters(parameters);
            attribute.setXmlName(xmlName);
            attribute.setMethodName(methodName);
            attribute.setDimension(isDimension);
            attribute.setLayoutParams(isLayoutParams);
            attribute.setLayoutParamsClass(layoutParamsClass);
            attribute.setFormats(formats);
            attribute.setEnumValues(enumValues);
            return attribute;
        }
    }
}
