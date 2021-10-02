package com.tyron.layoutpreview.model;

import java.util.Arrays;

public class Attribute {

    public static Builder builder() {
        return new Builder();
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

    private boolean isDimension;

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

    /**
     * Builder class to help constructing an Attribute
     */
    public static class Builder {

        private String xmlName;
        private String[] parameters;
        private int xmlOffset;
        private String methodName;
        private boolean isDimension;

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

        public Attribute build() {
            Attribute attribute = new Attribute();
            attribute.setXmlParameterOffset(xmlOffset);
            attribute.setParameters(parameters);
            attribute.setXmlName(xmlName);
            attribute.setMethodName(methodName);
            attribute.setDimension(isDimension);
            return attribute;
        }
    }
}
