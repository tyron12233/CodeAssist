package com.tyron.builder.internal.extensibility;

import com.tyron.builder.internal.metaobject.BeanDynamicObject;
import com.tyron.builder.internal.metaobject.CompositeDynamicObject;
import com.tyron.builder.internal.metaobject.DynamicInvokeResult;

import org.codehaus.groovy.runtime.GeneratedClosure;

import groovy.lang.Closure;

/**
 * Exposes methods for those properties whose value is a closure.
 *
 * TODO: use composition instead of inheritance
 */
public abstract class MixInClosurePropertiesAsMethodsDynamicObject extends CompositeDynamicObject {
    @Override
    public DynamicInvokeResult tryInvokeMethod(String name, Object... arguments) {
        DynamicInvokeResult result = super.tryInvokeMethod(name, arguments);
        if (result.isFound()) {
            return result;
        }

        DynamicInvokeResult propertyResult = tryGetProperty(name);
        if (propertyResult.isFound()) {
            Object property = propertyResult.getValue();
            if (property instanceof Closure) {
                Closure closure = (Closure) property;
                closure.setResolveStrategy(Closure.DELEGATE_FIRST);
                BeanDynamicObject dynamicObject = new BeanDynamicObject(closure);
                result = dynamicObject.tryInvokeMethod("doCall", arguments);
                if (!result.isFound() && !(closure instanceof GeneratedClosure)) {
                    return DynamicInvokeResult.found(closure.call(arguments));
                }
                return result;
            }
//            if (property instanceof NamedDomainObjectContainer && arguments.length == 1 && arguments[0] instanceof Closure) {
//                ((NamedDomainObjectContainer) property).configure((Closure) arguments[0]);
//                return DynamicInvokeResult.found();
//            }
        }
        return DynamicInvokeResult.notFound();
    }
}
