package javax.management;

import java.io.Serializable;

public interface QueryExp extends Serializable {
    boolean apply(ObjectName var1) throws BadStringOperationException, BadBinaryOpValueExpException, BadAttributeValueExpException, InvalidApplicationException;

    void setMBeanServer(MBeanServer var1);
}