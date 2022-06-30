package com.tyron.builder.groovy.scripts;

import com.tyron.builder.api.internal.DynamicObjectAware;
import com.tyron.builder.internal.logging.StandardOutputCapture;
import com.tyron.builder.internal.metaobject.AbstractDynamicObject;
import com.tyron.builder.internal.metaobject.BeanDynamicObject;
import com.tyron.builder.internal.metaobject.DynamicInvokeResult;
import com.tyron.builder.internal.metaobject.DynamicObject;
import com.tyron.builder.internal.metaobject.DynamicObjectUtil;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.internal.scripts.GradleScript;

import groovy.lang.Binding;
import groovy.lang.MissingMethodException;
import groovy.lang.MissingPropertyException;

import java.io.PrintStream;
import java.util.Map;

public abstract class BasicScript extends com.tyron.builder.groovy.scripts.Script implements com.tyron.builder.api.Script, DynamicObjectAware, GradleScript {
    private StandardOutputCapture standardOutputCapture;
    private Object target;
    private ScriptDynamicObject dynamicObject = new ScriptDynamicObject(this);

    @Override
    public void init(Object target, ServiceRegistry services) {
        standardOutputCapture = services.get(StandardOutputCapture.class);
        setScriptTarget(target);
    }

    public Object getScriptTarget() {
        return target;
    }

    private void setScriptTarget(Object target) {
        this.target = target;
        this.dynamicObject.setTarget(target);
    }

    @Override
    public StandardOutputCapture getStandardOutputCapture() {
        return standardOutputCapture;
    }

    public PrintStream getOut() {
        return System.out;
    }

    @Override
    public Object getProperty(String property) {
        return dynamicObject.getProperty(property);
    }

    @Override
    public void setProperty(String property, Object newValue) {
        dynamicObject.setProperty(property, newValue);
    }

    public Map<String, ?> getProperties() {
        return dynamicObject.getProperties();
    }

    public boolean hasProperty(String property) {
        return dynamicObject.hasProperty(property);
    }

    @Override
    public Object invokeMethod(String name, Object args) {
        return dynamicObject.invokeMethod(name, (Object[]) args);
    }

    @Override
    public DynamicObject getAsDynamicObject() {
        return dynamicObject;
    }

    /**
     * This is a performance optimization which avoids using BeanDynamicObject to wrap the Script object.
     * Using BeanDynamicObject would be wasteful, because most of the interesting properties and methods
     * are delegated to the script target. Doing this delegation explicitly avoids
     * us going through the methodMissing/propertyMissing protocol that BeanDynamicObject would use.
     */
    private static final class ScriptDynamicObject extends AbstractDynamicObject {

        private final Binding binding;
        private final DynamicObject scriptObject;
        private DynamicObject dynamicTarget;

        ScriptDynamicObject(BasicScript script) {
            this.binding = script.getBinding();
            scriptObject = new BeanDynamicObject(script).withNotImplementsMissing();
            dynamicTarget = scriptObject;
        }

        public void setTarget(Object target) {
            dynamicTarget = DynamicObjectUtil.asDynamicObject(target);
        }

        @Override
        public Map<String, ?> getProperties() {
            return dynamicTarget.getProperties();
        }

        @Override
        public boolean hasMethod(String name, Object... arguments) {
            return scriptObject.hasMethod(name, arguments) || dynamicTarget.hasMethod(name, arguments);
        }

        @Override
        public boolean hasProperty(String name) {
            return binding.hasVariable(name) || scriptObject.hasProperty(name) || dynamicTarget.hasProperty(name);
        }

        @Override
        public DynamicInvokeResult tryInvokeMethod(String name, Object... arguments) {
            DynamicInvokeResult result = scriptObject.tryInvokeMethod(name, arguments);
            if (result.isFound()) {
                return result;
            }
            return dynamicTarget.tryInvokeMethod(name, arguments);
        }

        @Override
        public DynamicInvokeResult tryGetProperty(String property) {
            if (binding.hasVariable(property)) {
                return DynamicInvokeResult.found(binding.getVariable(property));
            }
            DynamicInvokeResult result = scriptObject.tryGetProperty(property);
            if (result.isFound()) {
                return result;
            }
            return dynamicTarget.tryGetProperty(property);
        }

        @Override
        public DynamicInvokeResult trySetProperty(String property, Object newValue) {
            return dynamicTarget.trySetProperty(property, newValue);
        }

        @Override
        public MissingPropertyException getMissingProperty(String name) {
            return dynamicTarget.getMissingProperty(name);
        }

        @Override
        public MissingMethodException methodMissingException(String name, Object... params) {
            return dynamicTarget.methodMissingException(name, params);
        }

        @Override
        public MissingPropertyException setMissingProperty(String name) {
            return dynamicTarget.setMissingProperty(name);
        }

        @Override
        public String getDisplayName() {
            return dynamicTarget.toString();
        }
    }

}
