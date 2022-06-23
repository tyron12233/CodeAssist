package javax.management;

import java.io.ObjectInputStream;
import java.util.Set;

import javax.management.loading.ClassLoaderRepository;

public interface MBeanServer extends MBeanServerConnection {
    ObjectInstance createMBean(String var1, ObjectName var2) throws ReflectionException, InstanceAlreadyExistsException, MBeanRegistrationException, MBeanException, NotCompliantMBeanException;

    ObjectInstance createMBean(String var1, ObjectName var2, ObjectName var3) throws ReflectionException, InstanceAlreadyExistsException, MBeanRegistrationException, MBeanException, NotCompliantMBeanException, InstanceNotFoundException;

    ObjectInstance createMBean(String var1, ObjectName var2, Object[] var3, String[] var4) throws ReflectionException, InstanceAlreadyExistsException, MBeanRegistrationException, MBeanException, NotCompliantMBeanException;

    ObjectInstance createMBean(String var1, ObjectName var2, ObjectName var3, Object[] var4, String[] var5) throws ReflectionException, InstanceAlreadyExistsException, MBeanRegistrationException, MBeanException, NotCompliantMBeanException, InstanceNotFoundException;

    ObjectInstance registerMBean(Object var1, ObjectName var2) throws InstanceAlreadyExistsException, MBeanRegistrationException, NotCompliantMBeanException;

    void unregisterMBean(ObjectName var1) throws InstanceNotFoundException, MBeanRegistrationException;

    ObjectInstance getObjectInstance(ObjectName var1) throws InstanceNotFoundException;

    Set<ObjectInstance> queryMBeans(ObjectName var1, QueryExp var2);

    Set<ObjectName> queryNames(ObjectName var1, QueryExp var2);

    boolean isRegistered(ObjectName var1);

    Integer getMBeanCount();

    Object getAttribute(ObjectName var1, String var2) throws MBeanException, AttributeNotFoundException, InstanceNotFoundException, ReflectionException;

    AttributeList getAttributes(ObjectName var1, String[] var2) throws InstanceNotFoundException, ReflectionException;

    void setAttribute(ObjectName var1, Attribute var2) throws InstanceNotFoundException, AttributeNotFoundException, InvalidAttributeValueException, MBeanException, ReflectionException;

    AttributeList setAttributes(ObjectName var1, AttributeList var2) throws InstanceNotFoundException, ReflectionException;

    Object invoke(ObjectName var1, String var2, Object[] var3, String[] var4) throws InstanceNotFoundException, MBeanException, ReflectionException;

    String getDefaultDomain();

    String[] getDomains();

    void addNotificationListener(ObjectName var1, NotificationListener var2, NotificationFilter var3, Object var4) throws InstanceNotFoundException;

    void addNotificationListener(ObjectName var1, ObjectName var2, NotificationFilter var3, Object var4) throws InstanceNotFoundException;

    void removeNotificationListener(ObjectName var1, ObjectName var2) throws InstanceNotFoundException, ListenerNotFoundException;

    void removeNotificationListener(ObjectName var1, ObjectName var2, NotificationFilter var3, Object var4) throws InstanceNotFoundException, ListenerNotFoundException;

    void removeNotificationListener(ObjectName var1, NotificationListener var2) throws InstanceNotFoundException, ListenerNotFoundException;

    void removeNotificationListener(ObjectName var1, NotificationListener var2, NotificationFilter var3, Object var4) throws InstanceNotFoundException, ListenerNotFoundException;

    MBeanInfo getMBeanInfo(ObjectName var1) throws InstanceNotFoundException, IntrospectionException, ReflectionException;

    boolean isInstanceOf(ObjectName var1, String var2) throws InstanceNotFoundException;

    Object instantiate(String var1) throws ReflectionException, MBeanException;

    Object instantiate(String var1, ObjectName var2) throws ReflectionException, MBeanException, InstanceNotFoundException;

    Object instantiate(String var1, Object[] var2, String[] var3) throws ReflectionException, MBeanException;

    Object instantiate(String var1, ObjectName var2, Object[] var3, String[] var4) throws ReflectionException, MBeanException, InstanceNotFoundException;

    /** @deprecated */
    @Deprecated(
            since = "1.5"
    )
    default ObjectInputStream deserialize(ObjectName name, byte[] data) throws InstanceNotFoundException, OperationsException {
        throw new UnsupportedOperationException("Not supported.");
    }

    /** @deprecated */
    @Deprecated(
            since = "1.5"
    )
    default ObjectInputStream deserialize(String className, byte[] data) throws OperationsException, ReflectionException {
        throw new UnsupportedOperationException("Not supported.");
    }

    /** @deprecated */
    @Deprecated(
            since = "1.5"
    )
    default ObjectInputStream deserialize(String className, ObjectName loaderName, byte[] data) throws InstanceNotFoundException, OperationsException, ReflectionException {
        throw new UnsupportedOperationException("Not supported.");
    }

    ClassLoader getClassLoaderFor(ObjectName var1) throws InstanceNotFoundException;

    ClassLoader getClassLoader(ObjectName var1) throws InstanceNotFoundException;

    ClassLoaderRepository getClassLoaderRepository();
}
