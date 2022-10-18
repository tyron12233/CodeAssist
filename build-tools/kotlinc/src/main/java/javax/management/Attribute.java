package javax.management;

import java.io.Serializable;

public class Attribute implements Serializable {
    private static final long serialVersionUID = 2484220110589082382L;
    private String name;
    private Object value = null;

    public Attribute(String name, Object value) {
        if (name == null) {
            throw new RuntimeOperationsException(new IllegalArgumentException("Attribute name cannot be null "));
        } else {
            this.name = name;
            this.value = value;
        }
    }

    public String getName() {
        return this.name;
    }

    public Object getValue() {
        return this.value;
    }

    public boolean equals(Object object) {
        if (!(object instanceof Attribute)) {
            return false;
        } else {
            Attribute val = (Attribute)object;
            if (this.value == null) {
                return val.getValue() == null ? this.name.equals(val.getName()) : false;
            } else {
                return this.name.equals(val.getName()) && this.value.equals(val.getValue());
            }
        }
    }

    public int hashCode() {
        return this.name.hashCode() ^ (this.value == null ? 0 : this.value.hashCode());
    }

    public String toString() {
        String var10000 = this.getName();
        return var10000 + " = " + this.getValue();
    }
}
