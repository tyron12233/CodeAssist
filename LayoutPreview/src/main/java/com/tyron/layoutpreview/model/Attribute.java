package com.tyron.layoutpreview.model;

public class Attribute {

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
}
