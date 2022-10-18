package java.lang.management;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
//import javax.management.DynamicMBean;
//import javax.management.InstanceAlreadyExistsException;
//import javax.management.InstanceNotFoundException;
//import javax.management.JMX;
//import javax.management.MBeanRegistrationException;
//import javax.management.MBeanServer;
//import javax.management.MBeanServerConnection;
//import javax.management.MBeanServerFactory;
//import javax.management.MBeanServerPermission;
//import javax.management.MalformedObjectNameException;
//import javax.management.NotCompliantMBeanException;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.InstanceAlreadyExistsException;
import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.InvalidAttributeValueException;
import javax.management.ListenerNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.NotCompliantMBeanException;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.QueryExp;
import javax.management.ReflectionException;
import javax.management.loading.ClassLoaderRepository;
//import javax.management.ObjectName;
//import javax.management.QueryExp;
//import javax.management.StandardEmitterMBean;
//import javax.management.StandardMBean;
//import jdk.internal.misc.VM;
//import sun.management.Util;
//import sun.management.spi.PlatformMBeanProvider;
//import sun.management.spi.PlatformMBeanProvider.PlatformComponent;

public class ManagementFactory {
    public static final String CLASS_LOADING_MXBEAN_NAME = "java.lang:type=ClassLoading";
    public static final String COMPILATION_MXBEAN_NAME = "java.lang:type=Compilation";
    public static final String MEMORY_MXBEAN_NAME = "java.lang:type=Memory";
    public static final String OPERATING_SYSTEM_MXBEAN_NAME = "java.lang:type=OperatingSystem";
    public static final String RUNTIME_MXBEAN_NAME = "java.lang:type=Runtime";
    public static final String THREAD_MXBEAN_NAME = "java.lang:type=Threading";
    public static final String GARBAGE_COLLECTOR_MXBEAN_DOMAIN_TYPE = "java.lang:type=GarbageCollector";
    public static final String MEMORY_MANAGER_MXBEAN_DOMAIN_TYPE = "java.lang:type=MemoryManager";
    public static final String MEMORY_POOL_MXBEAN_DOMAIN_TYPE = "java.lang:type=MemoryPool";
//    private static MBeanServer platformMBeanServer;
    private static final String NOTIF_EMITTER = "javax.management.NotificationEmitter";

    private ManagementFactory() {
    }

    public static ClassLoadingMXBean getClassLoadingMXBean() {
        return new ClassLoadingMXBean() {
            @Override
            public long getTotalLoadedClassCount() {
                return 0;
            }

            @Override
            public int getLoadedClassCount() {
                return 0;
            }

            @Override
            public long getUnloadedClassCount() {
                return 0;
            }

            @Override
            public boolean isVerbose() {
                return false;
            }

            @Override
            public void setVerbose(boolean value) {

            }
        };
    }
//
    public static MemoryMXBean getMemoryMXBean() {
        return new MemoryMXBean() {
            @Override
            public int getObjectPendingFinalizationCount() {
                return 0;
            }

            @Override
            public MemoryUsage getHeapMemoryUsage() {
                return new MemoryUsage(0, Runtime.getRuntime().freeMemory(), Runtime.getRuntime().freeMemory(), Runtime.getRuntime()
                        .maxMemory());
            }

            @Override
            public MemoryUsage getNonHeapMemoryUsage() {
                return new MemoryUsage(0, Runtime.getRuntime().freeMemory(), Runtime.getRuntime().freeMemory(), Runtime.getRuntime()
                        .maxMemory());
            }

            @Override
            public boolean isVerbose() {
                return false;
            }

            @Override
            public void setVerbose(boolean value) {

            }

            @Override
            public void gc() {

            }
        };
    }

    public static ThreadMXBean getThreadMXBean() {
        return new ThreadMXBean() {
            @Override
            public int getThreadCount() {
                return Thread.activeCount();
            }

            @Override
            public int getPeakThreadCount() {
                return 0;
            }

            @Override
            public long getTotalStartedThreadCount() {
                return 0;
            }

            @Override
            public int getDaemonThreadCount() {
                return 0;
            }

            @Override
            public long[] getAllThreadIds() {
                return new long[0];
            }

            @Override
            public ThreadInfo getThreadInfo(long id) {
                return null;
            }

            @Override
            public ThreadInfo[] getThreadInfo(long[] ids) {
                return new ThreadInfo[0];
            }

            @Override
            public ThreadInfo getThreadInfo(long id, int maxDepth) {
                return null;
            }

            @Override
            public ThreadInfo[] getThreadInfo(long[] ids, int maxDepth) {
                return new ThreadInfo[0];
            }

            @Override
            public boolean isThreadContentionMonitoringSupported() {
                return false;
            }

            @Override
            public boolean isThreadContentionMonitoringEnabled() {
                return false;
            }

            @Override
            public void setThreadContentionMonitoringEnabled(boolean enable) {

            }

            @Override
            public long getCurrentThreadCpuTime() {
                return 0;
            }

            @Override
            public long getCurrentThreadUserTime() {
                return 0;
            }

            @Override
            public long getThreadCpuTime(long id) {
                return 0;
            }

            @Override
            public long getThreadUserTime(long id) {
                return 0;
            }

            @Override
            public boolean isThreadCpuTimeSupported() {
                return false;
            }

            @Override
            public boolean isCurrentThreadCpuTimeSupported() {
                return false;
            }

            @Override
            public boolean isThreadCpuTimeEnabled() {
                return false;
            }

            @Override
            public void setThreadCpuTimeEnabled(boolean enable) {

            }

            @Override
            public long[] findMonitorDeadlockedThreads() {
                return new long[0];
            }

            @Override
            public void resetPeakThreadCount() {

            }

            @Override
            public long[] findDeadlockedThreads() {
                return new long[0];
            }

            @Override
            public boolean isObjectMonitorUsageSupported() {
                return false;
            }

            @Override
            public boolean isSynchronizerUsageSupported() {
                return false;
            }

            @Override
            public ThreadInfo[] getThreadInfo(long[] ids,
                                              boolean lockedMonitors,
                                              boolean lockedSynchronizers) {
                return new ThreadInfo[0];
            }

            @Override
            public ThreadInfo[] dumpAllThreads(boolean lockedMonitors,
                                               boolean lockedSynchronizers) {
                return new ThreadInfo[0];
            }
        };
    }

    public static RuntimeMXBean getRuntimeMXBean() {
        return new RuntimeMXBean() {
            @Override
            public String getName() {
                return null;
            }

            @Override
            public String getVmName() {
                return null;
            }

            @Override
            public String getVmVendor() {
                return null;
            }

            @Override
            public String getVmVersion() {
                return null;
            }

            @Override
            public String getSpecName() {
                return null;
            }

            @Override
            public String getSpecVendor() {
                return null;
            }

            @Override
            public String getSpecVersion() {
                return null;
            }

            @Override
            public String getManagementSpecVersion() {
                return null;
            }

            @Override
            public String getClassPath() {
                return "";
            }

            @Override
            public String getLibraryPath() {
                return "";
            }

            @Override
            public boolean isBootClassPathSupported() {
                return false;
            }

            @Override
            public String getBootClassPath() {
                return null;
            }

            @Override
            public List<String> getInputArguments() {
                return emptyList();
            }

            @Override
            public long getUptime() {
                return 0;
            }

            @Override
            public long getStartTime() {
                return 0;
            }

            @Override
            public Map<String, String> getSystemProperties() {
                return null;
            }
        };
    }
//
//    public static CompilationMXBean getCompilationMXBean() {
//        return (CompilationMXBean)getPlatformMXBean(CompilationMXBean.class);
//    }
//
//    public static OperatingSystemMXBean getOperatingSystemMXBean() {
//        return (OperatingSystemMXBean)getPlatformMXBean(OperatingSystemMXBean.class);
//    }
//
//    public static List<MemoryPoolMXBean> getMemoryPoolMXBeans() {
//        return getPlatformMXBeans(MemoryPoolMXBean.class);
//    }
//
//    public static List<MemoryManagerMXBean> getMemoryManagerMXBeans() {
//        return getPlatformMXBeans(MemoryManagerMXBean.class);
//    }
//
    public static List<Object> getGarbageCollectorMXBeans() {
        return Collections.emptyList();
    }

    public static synchronized MBeanServer getPlatformMBeanServer() {
        return new MBeanServer() {
            @Override
            public ObjectInstance createMBean(String var1,
                                              ObjectName var2) throws ReflectionException, InstanceAlreadyExistsException, MBeanRegistrationException, MBeanException, NotCompliantMBeanException {
                    return null;
            }

            @Override
            public ObjectInstance createMBean(String var1,
                                              ObjectName var2,
                                              ObjectName var3) throws ReflectionException, InstanceAlreadyExistsException, MBeanRegistrationException, MBeanException, NotCompliantMBeanException, InstanceNotFoundException {
                return null;
            }

            @Override
            public ObjectInstance createMBean(String var1,
                                              ObjectName var2,
                                              Object[] var3,
                                              String[] var4) throws ReflectionException, InstanceAlreadyExistsException, MBeanRegistrationException, MBeanException, NotCompliantMBeanException {
                return null;
            }

            @Override
            public ObjectInstance createMBean(String var1,
                                              ObjectName var2,
                                              ObjectName var3,
                                              Object[] var4,
                                              String[] var5) throws ReflectionException, InstanceAlreadyExistsException, MBeanRegistrationException, MBeanException, NotCompliantMBeanException, InstanceNotFoundException {
                return null;
            }

            @Override
            public ObjectInstance registerMBean(Object var1,
                                                ObjectName var2) throws InstanceAlreadyExistsException, MBeanRegistrationException, NotCompliantMBeanException {
                return null;
            }

            @Override
            public void unregisterMBean(ObjectName var1) throws InstanceNotFoundException,
                    MBeanRegistrationException {

            }

            @Override
            public ObjectInstance getObjectInstance(ObjectName var1) throws InstanceNotFoundException {
                return null;
            }

            @Override
            public Set<ObjectInstance> queryMBeans(ObjectName var1, QueryExp var2) {
                return emptySet();
            }

            @Override
            public Set<ObjectName> queryNames(ObjectName var1, QueryExp var2) {
                return emptySet();
            }

            @Override
            public boolean isRegistered(ObjectName var1) {
                return false;
            }

            @Override
            public Integer getMBeanCount() {
                return 0;
            }

            @Override
            public Object getAttribute(ObjectName var1,
                                       String var2) throws MBeanException,
                    AttributeNotFoundException, InstanceNotFoundException, ReflectionException {
                return new ConcurrentHashMap<>();
            }

            @Override
            public AttributeList getAttributes(ObjectName var1,
                                               String[] var2) throws InstanceNotFoundException, ReflectionException {
                return null;
            }

            @Override
            public void setAttribute(ObjectName var1,
                                     Attribute var2) throws InstanceNotFoundException,
                    AttributeNotFoundException, InvalidAttributeValueException, MBeanException,
                    ReflectionException {

            }

            @Override
            public AttributeList setAttributes(ObjectName var1,
                                               AttributeList var2) throws InstanceNotFoundException, ReflectionException {
                return null;
            }

            @Override
            public Object invoke(ObjectName var1,
                                 String var2,
                                 Object[] var3,
                                 String[] var4) throws InstanceNotFoundException, MBeanException,
                    ReflectionException {
                return null;
            }

            @Override
            public String getDefaultDomain() {
                return null;
            }

            @Override
            public String[] getDomains() {
                return new String[0];
            }

            @Override
            public void addNotificationListener(ObjectName var1,
                                                NotificationListener var2,
                                                NotificationFilter var3,
                                                Object var4) throws InstanceNotFoundException {

            }

            @Override
            public void addNotificationListener(ObjectName var1,
                                                ObjectName var2,
                                                NotificationFilter var3,
                                                Object var4) throws InstanceNotFoundException {

            }

            @Override
            public void removeNotificationListener(ObjectName var1,
                                                   ObjectName var2) throws InstanceNotFoundException, ListenerNotFoundException {

            }

            @Override
            public void removeNotificationListener(ObjectName var1,
                                                   ObjectName var2,
                                                   NotificationFilter var3,
                                                   Object var4) throws InstanceNotFoundException,
                    ListenerNotFoundException {

            }

            @Override
            public void removeNotificationListener(ObjectName var1,
                                                   NotificationListener var2) throws InstanceNotFoundException, ListenerNotFoundException {

            }

            @Override
            public void removeNotificationListener(ObjectName var1,
                                                   NotificationListener var2,
                                                   NotificationFilter var3,
                                                   Object var4) throws InstanceNotFoundException,
                    ListenerNotFoundException {

            }

            @Override
            public MBeanInfo getMBeanInfo(ObjectName var1) throws InstanceNotFoundException,
                    IntrospectionException, ReflectionException {
                return null;
            }

            @Override
            public boolean isInstanceOf(ObjectName var1,
                                        String var2) throws InstanceNotFoundException {
                return false;
            }

            @Override
            public Object instantiate(String var1) throws ReflectionException, MBeanException {
                return null;
            }

            @Override
            public Object instantiate(String var1,
                                      ObjectName var2) throws ReflectionException, MBeanException, InstanceNotFoundException {
                return null;
            }

            @Override
            public Object instantiate(String var1,
                                      Object[] var2,
                                      String[] var3) throws ReflectionException, MBeanException {
                return null;
            }

            @Override
            public Object instantiate(String var1,
                                      ObjectName var2,
                                      Object[] var3,
                                      String[] var4) throws ReflectionException, MBeanException, InstanceNotFoundException {
                return null;
            }

            @Override
            public ClassLoader getClassLoaderFor(ObjectName var1) throws InstanceNotFoundException {
                return null;
            }

            @Override
            public ClassLoader getClassLoader(ObjectName var1) throws InstanceNotFoundException {
                return null;
            }

            @Override
            public ClassLoaderRepository getClassLoaderRepository() {
                return null;
            }
        };
    }

//    public static <T> T newPlatformMXBeanProxy(MBeanServerConnection connection, String mxbeanName, Class<T> mxbeanInterface) throws IOException {
//        ClassLoader loader = (ClassLoader)AccessController.doPrivileged(
//                (PrivilegedAction<ClassLoader>) () -> mxbeanInterface.getClassLoader());
//        if (!VM.isSystemDomainLoader(loader)) {
//            throw new IllegalArgumentException(mxbeanName + " is not a platform MXBean");
//        } else {
//            try {
//                ObjectName objName = new ObjectName(mxbeanName);
//                String intfName = mxbeanInterface.getName();
//                if (!isInstanceOf(connection, objName, intfName)) {
//                    throw new IllegalArgumentException(mxbeanName + " is not an instance of " + mxbeanInterface);
//                } else {
//                    boolean emitter = connection.isInstanceOf(objName, "javax.management.NotificationEmitter");
//                    return JMX.newMXBeanProxy(connection, objName, mxbeanInterface, emitter);
//                }
//            } catch (MalformedObjectNameException | InstanceNotFoundException var8) {
//                throw new IllegalArgumentException(var8);
//            }
//        }
//    }
//
//    private static boolean isInstanceOf(MBeanServerConnection connection, ObjectName objName, String intfName) throws InstanceNotFoundException, IOException {
//        return "java.util.logging.LoggingMXBean".equals(intfName) &&
//               connection.isInstanceOf(objName, PlatformLoggingMXBean.class.getName()) ||
//               connection.isInstanceOf(objName, intfName);
//    }
//
//    public static <T extends PlatformManagedObject> T getPlatformMXBean(Class<T> mxbeanInterface) {
//        PlatformComponent<?> pc = ManagementFactory.PlatformMBeanFinder.findSingleton(mxbeanInterface);
//        List<? extends T> mbeans = pc.getMBeans(mxbeanInterface);
//
//        assert mbeans.isEmpty() || mbeans.size() == 1;
//
//        return mbeans.isEmpty() ? null : (T) mbeans.get(0);
//    }
//
//    public static <T extends PlatformManagedObject> List<T> getPlatformMXBeans(Class<T> mxbeanInterface) {
//        PlatformComponent<?> pc = ManagementFactory.PlatformMBeanFinder.findFirst(mxbeanInterface);
//        if (pc == null) {
//            throw new IllegalArgumentException(mxbeanInterface.getName() + " is not a platform management interface");
//        } else {
//            return (List)platformComponents().stream().flatMap((p) -> {
//                return p.getMBeans(mxbeanInterface).stream();
//            }).collect(Collectors.toList());
//        }
//    }
//
//    public static <T extends PlatformManagedObject> T getPlatformMXBean(MBeanServerConnection connection, Class<T> mxbeanInterface) throws IOException {
//        PlatformComponent<?> pc = ManagementFactory.PlatformMBeanFinder.findSingleton(mxbeanInterface);
//        return (T) newPlatformMXBeanProxy(connection, pc.getObjectNamePattern(), mxbeanInterface);
//    }
//
//    public static <T extends PlatformManagedObject> List<T> getPlatformMXBeans(MBeanServerConnection connection, Class<T> mxbeanInterface) throws IOException {
//        PlatformComponent<?> pc = ManagementFactory.PlatformMBeanFinder.findFirst(mxbeanInterface);
//        if (pc == null) {
//            throw new IllegalArgumentException(mxbeanInterface.getName() + " is not a platform management interface");
//        } else {
//            Stream<String> names = Stream.empty();
//
//            PlatformComponent p;
//            for(Iterator var4 = platformComponents().iterator(); var4.hasNext(); names = Stream.concat(names, getProxyNames(p, connection, mxbeanInterface))) {
//                p = (PlatformComponent)var4.next();
//            }
//
//            Set objectNames = (Set)names.collect(Collectors.toSet());
//            if (objectNames.isEmpty()) {
//                return Collections.emptyList();
//            } else {
//                List<T> proxies = new ArrayList();
//                Iterator var6 = objectNames.iterator();
//
//                while(var6.hasNext()) {
//                    String name = (String)var6.next();
//                    proxies.add((T) newPlatformMXBeanProxy(connection, name, mxbeanInterface));
//                }
//
//                return proxies;
//            }
//        }
//    }
//
//    private static Stream<String> getProxyNames(PlatformComponent<?> pc, MBeanServerConnection conn, Class<?> intf) throws IOException {
//        if (pc.mbeanInterfaceNames().contains(intf.getName())) {
//            return pc.isSingleton() ? Stream.of(pc.getObjectNamePattern()) : conn.queryNames(Util.newObjectName(pc.getObjectNamePattern()), (QueryExp)null).stream().map(ObjectName::getCanonicalName);
//        } else {
//            return Stream.empty();
//        }
//    }
//
//    public static Set<Class<? extends PlatformManagedObject>> getPlatformManagementInterfaces() {
//        Stream<Class<? extends PlatformManagedObject>> pmos = platformComponents().stream().flatMap((pc) -> {
//            return pc.mbeanInterfaces().stream();
//        }).filter((clazz) -> {
//            return PlatformManagedObject.class.isAssignableFrom(clazz);
//        }).map((clazz) -> {
//            return clazz.asSubclass(PlatformManagedObject.class);
//        });
//        return (Set)pmos.collect(Collectors.toSet());
//    }
//
//    private static void addMXBean(MBeanServer mbs, String name, Object pmo) {
//        try {
//            ObjectName oname = ObjectName.getInstance(name);
//            AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
//                Object dmbean;
//                if (pmo instanceof DynamicMBean) {
//                    dmbean = (DynamicMBean)DynamicMBean.class.cast(pmo);
//                } else if (pmo instanceof NotificationEmitter) {
//                    dmbean = new StandardEmitterMBean(pmo, (Class)null, true, (NotificationEmitter)pmo);
//                } else {
//                    dmbean = new StandardMBean(pmo, (Class)null, true);
//                }
//
//                try {
//                    mbs.registerMBean(dmbean, oname);
//                } catch (InstanceAlreadyExistsException | MBeanRegistrationException | NotCompliantMBeanException e) {
//                    throw new RuntimeException(e);
//                }
//                return null;
//            });
//        } catch (MalformedObjectNameException var4) {
//            throw new IllegalArgumentException(var4);
//        }
//    }
//
//    private static Collection<PlatformComponent<?>> platformComponents() {
//        return ManagementFactory.PlatformMBeanFinder.getMap().values();
//    }
//
//    static {
//        AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
//            System.loadLibrary("management");
//            return null;
//        });
//    }
//
//    private static class PlatformMBeanFinder {
//        private static final Map componentMap;
//
//        private PlatformMBeanFinder() {
//        }
//
//        static Map<String, PlatformComponent<?>> getMap() {
//            return componentMap;
//        }
//
//        private static Stream<PlatformComponent<?>> toPlatformComponentStream(PlatformMBeanProvider provider) {
//            return ((Map)provider.getPlatformComponentList().stream().collect(Collectors.toMap(PlatformComponent::getObjectNamePattern, Function.identity(), (p1, p2) -> {
//                throw new InternalError(p1.getObjectNamePattern() + " has been used as key for " + p1 + ", it cannot be reused for " + p2);
//            }))).values().stream();
//        }
//
//        static PlatformComponent<?> findFirst(Class<?> mbeanIntf) {
//            String name = mbeanIntf.getName();
//            Optional<PlatformComponent<?>> op = getMap().values().stream().filter((pc) -> {
//                return pc.mbeanInterfaceNames().contains(name);
//            }).findFirst();
//            return op.isPresent() ? (PlatformComponent)op.get() : null;
//        }
//
//        static PlatformComponent<?> findSingleton(Class<?> mbeanIntf) {
//            String name = mbeanIntf.getName();
//            Optional<PlatformComponent<?>> op = getMap().values().stream().filter((pc) -> {
//                return pc.mbeanInterfaceNames().contains(name);
//            }).reduce((p1, p2) -> {
//                if (p2 != null) {
//                    throw new IllegalArgumentException(mbeanIntf.getName() + " can have more than one instance");
//                } else {
//                    return p1;
//                }
//            });
//            PlatformComponent<?> singleton = op.isPresent() ? (PlatformComponent)op.get() : null;
//            if (singleton == null) {
//                throw new IllegalArgumentException(mbeanIntf.getName() + " is not a platform management interface");
//            } else if (!singleton.isSingleton()) {
//                throw new IllegalArgumentException(mbeanIntf.getName() + " can have more than one instance");
//            } else {
//                return singleton;
//            }
//        }
//
//        static {
//            List providers = (List)AccessController.doPrivileged(
//                    (PrivilegedAction<List<PlatformMBeanProvider>>) () -> {
//                        ArrayList all = new ArrayList();
//                        ServiceLoader var10000 = ServiceLoader.loadInstalled(PlatformMBeanProvider.class);
//                        Objects.requireNonNull(all);
//                        var10000.forEach(all::add);
//                        all.add(new DefaultPlatformMBeanProvider());
//                        return all;
//                    }, (AccessControlContext)null, new FilePermission("<<ALL FILES>>", "read"), new RuntimePermission("sun.management.spi.PlatformMBeanProvider.subclass"));
//            componentMap = (Map)providers.stream().flatMap((p) -> toPlatformComponentStream(
//                    (PlatformMBeanProvider) p)).collect(Collectors.toMap(o -> o, Function.identity(), (p1, p2) -> p1));
//        }
//    }
}
