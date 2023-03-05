package javax.management;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.StreamCorruptedException;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

public class MBeanInfo implements Cloneable, Serializable, DescriptorRead {
    static final long serialVersionUID = -6451021435135161911L;
    private transient Descriptor descriptor;
    private final String description;
    private final String className;
    private final MBeanAttributeInfo[] attributes;
    private final MBeanOperationInfo[] operations;
    private final MBeanConstructorInfo[] constructors;
    private final MBeanNotificationInfo[] notifications;
    private transient int hashCode;
    private final transient boolean arrayGettersSafe;
    private static final Map<Class<?>, Boolean> arrayGettersSafeMap = new WeakHashMap();

    public MBeanInfo(String className, String description, MBeanAttributeInfo[] attributes, MBeanConstructorInfo[] constructors, MBeanOperationInfo[] operations, MBeanNotificationInfo[] notifications) throws IllegalArgumentException {
        this(className, description, attributes, constructors, operations, notifications, (Descriptor)null);
    }

    public MBeanInfo(String className, String description, MBeanAttributeInfo[] attributes, MBeanConstructorInfo[] constructors, MBeanOperationInfo[] operations, MBeanNotificationInfo[] notifications, Descriptor descriptor) throws IllegalArgumentException {
        this.className = className;
        this.description = description;
        if (attributes == null) {
            attributes = MBeanAttributeInfo.NO_ATTRIBUTES;
        }

        this.attributes = attributes;
        if (operations == null) {
            operations = MBeanOperationInfo.NO_OPERATIONS;
        }

        this.operations = operations;
        if (constructors == null) {
            constructors = MBeanConstructorInfo.NO_CONSTRUCTORS;
        }

        this.constructors = constructors;
        if (notifications == null) {
            notifications = MBeanNotificationInfo.NO_NOTIFICATIONS;
        }

        this.notifications = notifications;
        if (descriptor == null) {
            descriptor = ImmutableDescriptor.EMPTY_DESCRIPTOR;
        }

        this.descriptor = (Descriptor)descriptor;
        this.arrayGettersSafe = arrayGettersSafe(this.getClass(), MBeanInfo.class);
    }

    public Object clone() {
        try {
            return super.clone();
        } catch (CloneNotSupportedException var2) {
            return null;
        }
    }

    public String getClassName() {
        return this.className;
    }

    public String getDescription() {
        return this.description;
    }

    public MBeanAttributeInfo[] getAttributes() {
        MBeanAttributeInfo[] as = this.nonNullAttributes();
        return as.length == 0 ? as : (MBeanAttributeInfo[])as.clone();
    }

    private MBeanAttributeInfo[] fastGetAttributes() {
        return this.arrayGettersSafe ? this.nonNullAttributes() : this.getAttributes();
    }

    private MBeanAttributeInfo[] nonNullAttributes() {
        return this.attributes == null ? MBeanAttributeInfo.NO_ATTRIBUTES : this.attributes;
    }

    public MBeanOperationInfo[] getOperations() {
        MBeanOperationInfo[] os = this.nonNullOperations();
        return os.length == 0 ? os : (MBeanOperationInfo[])os.clone();
    }

    private MBeanOperationInfo[] fastGetOperations() {
        return this.arrayGettersSafe ? this.nonNullOperations() : this.getOperations();
    }

    private MBeanOperationInfo[] nonNullOperations() {
        return this.operations == null ? MBeanOperationInfo.NO_OPERATIONS : this.operations;
    }

    public MBeanConstructorInfo[] getConstructors() {
        MBeanConstructorInfo[] cs = this.nonNullConstructors();
        return cs.length == 0 ? cs : (MBeanConstructorInfo[])cs.clone();
    }

    private MBeanConstructorInfo[] fastGetConstructors() {
        return this.arrayGettersSafe ? this.nonNullConstructors() : this.getConstructors();
    }

    private MBeanConstructorInfo[] nonNullConstructors() {
        return this.constructors == null ? MBeanConstructorInfo.NO_CONSTRUCTORS : this.constructors;
    }

    public MBeanNotificationInfo[] getNotifications() {
        MBeanNotificationInfo[] ns = this.nonNullNotifications();
        return ns.length == 0 ? ns : (MBeanNotificationInfo[])ns.clone();
    }

    private MBeanNotificationInfo[] fastGetNotifications() {
        return this.arrayGettersSafe ? this.nonNullNotifications() : this.getNotifications();
    }

    private MBeanNotificationInfo[] nonNullNotifications() {
        return this.notifications == null ? MBeanNotificationInfo.NO_NOTIFICATIONS : this.notifications;
    }

    public Descriptor getDescriptor() {
        return (Descriptor)ImmutableDescriptor.nonNullDescriptor(this.descriptor).clone();
    }

    public String toString() {
        String var10000 = this.getClass().getName();
        return var10000 + "[description=" + this.getDescription() + ", attributes=" + Arrays.asList(this.fastGetAttributes()) + ", constructors=" + Arrays.asList(this.fastGetConstructors()) + ", operations=" + Arrays.asList(this.fastGetOperations()) + ", notifications=" + Arrays.asList(this.fastGetNotifications()) + ", descriptor=" + this.getDescriptor() + "]";
    }

    public boolean equals(Object o) {
        if (o == this) {
            return true;
        } else if (!(o instanceof MBeanInfo)) {
            return false;
        } else {
            MBeanInfo p = (MBeanInfo)o;
            if (isEqual(this.getClassName(), p.getClassName()) && isEqual(this.getDescription(), p.getDescription()) && this.getDescriptor().equals(p.getDescriptor())) {
                return Arrays.equals(p.fastGetAttributes(), this.fastGetAttributes()) && Arrays.equals(p.fastGetOperations(), this.fastGetOperations()) && Arrays.equals(p.fastGetConstructors(), this.fastGetConstructors()) && Arrays.equals(p.fastGetNotifications(), this.fastGetNotifications());
            } else {
                return false;
            }
        }
    }

    public int hashCode() {
        if (this.hashCode != 0) {
            return this.hashCode;
        } else {
            this.hashCode = Objects.hash(new Object[]{this.getClassName(), this.getDescriptor()}) ^ Arrays.hashCode(this.fastGetAttributes()) ^ Arrays.hashCode(this.fastGetOperations()) ^ Arrays.hashCode(this.fastGetConstructors()) ^ Arrays.hashCode(this.fastGetNotifications());
            return this.hashCode;
        }
    }

    static boolean arrayGettersSafe(Class<?> subclass, Class<?> immutableClass) {
        if (subclass == immutableClass) {
            return true;
        } else {
            synchronized(arrayGettersSafeMap) {
                Boolean safe = (Boolean)arrayGettersSafeMap.get(subclass);
                if (safe == null) {
                    try {
                        MBeanInfo.ArrayGettersSafeAction action = new MBeanInfo.ArrayGettersSafeAction(subclass, immutableClass);
                        safe = (Boolean)AccessController.doPrivileged(action);
                    } catch (Exception var6) {
                        safe = false;
                    }

                    arrayGettersSafeMap.put(subclass, safe);
                }

                return safe;
            }
        }
    }

    private static boolean isEqual(String s1, String s2) {
        boolean ret;
        if (s1 == null) {
            ret = s2 == null;
        } else {
            ret = s1.equals(s2);
        }

        return ret;
    }

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        if (this.descriptor.getClass() == ImmutableDescriptor.class) {
            out.write(1);
            String[] names = this.descriptor.getFieldNames();
            out.writeObject(names);
            out.writeObject(this.descriptor.getFieldValues(names));
        } else {
            out.write(0);
            out.writeObject(this.descriptor);
        }

    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        switch(in.read()) {
        case -1:
            this.descriptor = ImmutableDescriptor.EMPTY_DESCRIPTOR;
            break;
        case 0:
            this.descriptor = (Descriptor)in.readObject();
            if (this.descriptor == null) {
                this.descriptor = ImmutableDescriptor.EMPTY_DESCRIPTOR;
            }
            break;
        case 1:
            String[] names = (String[])in.readObject();
            Object[] values = (Object[])in.readObject();
            this.descriptor = names.length == 0 ? ImmutableDescriptor.EMPTY_DESCRIPTOR : new ImmutableDescriptor(names, values);
            break;
        default:
            throw new StreamCorruptedException("Got unexpected byte.");
        }

    }

    private static class ArrayGettersSafeAction implements PrivilegedAction<Boolean> {
        private final Class<?> subclass;
        private final Class<?> immutableClass;

        ArrayGettersSafeAction(Class<?> subclass, Class<?> immutableClass) {
            this.subclass = subclass;
            this.immutableClass = immutableClass;
        }

        public Boolean run() {
            Method[] methods = this.immutableClass.getMethods();

            for(int i = 0; i < methods.length; ++i) {
                Method method = methods[i];
                String methodName = method.getName();
                if (methodName.startsWith("get") && method.getParameterTypes().length == 0 && method.getReturnType().isArray()) {
                    try {
                        Method submethod = this.subclass.getMethod(methodName);
                        if (!submethod.equals(method)) {
                            return false;
                        }
                    } catch (NoSuchMethodException var6) {
                        return false;
                    }
                }
            }

            return true;
        }
    }
}
