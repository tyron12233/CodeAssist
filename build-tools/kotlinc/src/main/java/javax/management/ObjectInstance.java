package javax.management;

import java.io.Serializable;

public class ObjectInstance implements Serializable {
    private static final long serialVersionUID = -4099952623687795850L;
    private ObjectName name;
    private String className;

    public ObjectInstance(String objectName, String className) throws MalformedObjectNameException {
        this(new ObjectName(objectName), className);
    }

    public ObjectInstance(ObjectName objectName, String className) {
        if (objectName.isPattern()) {
            IllegalArgumentException iae = new IllegalArgumentException("Invalid name->" + objectName.toString());
            throw new RuntimeOperationsException(iae);
        } else {
            this.name = objectName;
            this.className = className;
        }
    }

    public boolean equals(Object object) {
        if (!(object instanceof ObjectInstance)) {
            return false;
        } else {
            ObjectInstance val = (ObjectInstance)object;
            if (!this.name.equals(val.getObjectName())) {
                return false;
            } else if (this.className == null) {
                return val.getClassName() == null;
            } else {
                return this.className.equals(val.getClassName());
            }
        }
    }

    public int hashCode() {
        int classHash = this.className == null ? 0 : this.className.hashCode();
        return this.name.hashCode() ^ classHash;
    }

    public ObjectName getObjectName() {
        return this.name;
    }

    public String getClassName() {
        return this.className;
    }

    public String toString() {
        String var10000 = this.getClassName();
        return var10000 + "[" + this.getObjectName() + "]";
    }
}
