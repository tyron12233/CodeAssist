package javax.management.loading;

public interface ClassLoaderRepository {
    Class<?> loadClass(String var1) throws ClassNotFoundException;

    Class<?> loadClassWithout(ClassLoader var1, String var2) throws ClassNotFoundException;

    Class<?> loadClassBefore(ClassLoader var1, String var2) throws ClassNotFoundException;
}
