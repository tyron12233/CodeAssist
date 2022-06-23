package javax.management;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectInputStream.GetField;

public class BadAttributeValueExpException extends Exception {
    private static final long serialVersionUID = -3105272988410493376L;
    private Object val;

    public BadAttributeValueExpException(Object val) {
        this.val = val == null ? null : val.toString();
    }

    public String toString() {
        return "BadAttributeValueException: " + this.val;
    }

    private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
        GetField gf = ois.readFields();
        Object valObj = gf.get("val", (Object)null);
        if (valObj == null) {
            this.val = null;
        } else if (valObj instanceof String) {
            this.val = valObj;
        } else if (System.getSecurityManager() != null && !(valObj instanceof Long) && !(valObj instanceof Integer) && !(valObj instanceof Float) && !(valObj instanceof Double) && !(valObj instanceof Byte) && !(valObj instanceof Short) && !(valObj instanceof Boolean)) {
            int var10001 = System.identityHashCode(valObj);
            this.val = var10001 + "@" + valObj.getClass().getName();
        } else {
            this.val = valObj.toString();
        }

    }
}
