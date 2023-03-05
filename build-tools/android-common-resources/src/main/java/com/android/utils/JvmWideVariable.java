package com.android.utils;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.TypeParameter;
import com.google.common.reflect.TypeToken;
import java.lang.management.ManagementFactory;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.management.AttributeNotFoundException;
import javax.management.InstanceAlreadyExistsException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;
import javax.management.ReflectionException;

/**
 * A proxy object that can access a JVM-wide variable. A JVM-wide variable is a variable that can be
 * accessed from everywhere in the JVM, even when the JVM contains classes that are loaded multiple
 * times by different class loaders.
 *
 * <p>This class addresses the limitation of static variables with regard to their scopes: Static
 * variables are unique per class loader of the class that defines them. When the defining class of
 * a static variable is loaded multiple times by different class loaders, the variable cannot be
 * accessed from another class loader.
 *
 * <p>JVM-wide variables, on the other hand, allow a class loaded by different class loaders to
 * still reference the same variable. That is, changes to a JVM-wide variable made from a class
 * loaded by one class loader can be seen from the same class loaded by a different class loader.
 *
 * <p>A {@link JvmWideVariable} instance should typically be assigned to some static field of a
 * class, not to an instance field or a local variable within a method, since the actual JVM-wide
 * variable will not automatically be garbage-collected when it is no longer used, as one would have
 * expected from an instance field or a local variable.
 *
 * <p>The usage of this class is as follows. Suppose we previously used a static variable:
 *
 * <pre>{@code
 * public final class Counter {
 *   public static final AtomicInteger COUNT = new AtomicInteger(0);
 * }
 * }</pre>
 *
 * <p>We can then convert the static variable into a JVM-wide variable:
 *
 * <pre>{@code
 * public final class Counter {
 *    public static final JvmWideVariable<AtomicInteger> COUNT =
 *      new JvmWideVariable<>(
 *        my.package.Counter.class, "COUNT", AtomicInteger.class, new AtomicInteger(0));
 * }
 * }</pre>
 *
 * <p>Note that in the above example, {@code Counter.COUNT} is still a static variable of {@code
 * Counter}, with the previously discussed limitation (not only the {@code Counter} class but even
 * the {@code JvmWideVariable} class itself might be loaded multiple times by different class
 * loaders). What has changed is that {@code Counter.COUNT} is now able to access a JVM-wide
 * variable of type {@code AtomicInteger}. (The type of the JVM-variable after the conversion is the
 * same as the type of the static variable before the conversion.)
 *
 * <p>Where the context is clear, it might be easier to refer to variables of type {@code
 * JvmWideVariable} as JVM-wide variables, although strictly speaking they are not, but through them
 * we can access JVM-wide variables.
 *
 * <p>Also note that the type of a JVM-wide variable must be loaded once by a single class loader.
 * If a JVM-wide variable’s type is loaded multiple times by different class loaders, it may result
 * in runtime casting exceptions as they are essentially different types.
 *
 * <p>Since a JVM-wide variable is by definition a shared variable, the users of this class need to
 * provide proper synchronization when working with the value of the JVM-wide variable, for example
 * by using thread-safe types for its value such as {@link AtomicInteger} and {@link ConcurrentMap},
 * using (implicit or explicit) locks where the locks need to work across class loaders, or using
 * the {@link #executeCallableSynchronously(Callable)} method and the like provided by this class.
 *
 * <p>For example, suppose we have a static variable of a non-thread-safe type (e.g., {@link
 * Integer}) and we use a synchronized block when modifying the variable:
 *
 * <pre>{@code
 * public final class Counter {
 *   public static Integer COUNT = 0;
 *   public static synchronized void increaseCounter() {
 *     COUNT++;
 *   }
 * }
 * }</pre>
 *
 * <p>Then, the converted JVM-wide implementation can be as follows:
 *
 * <pre>{@code
 * public final class Counter {
 *   public static final JvmWideVariable<Integer> COUNT =
 *       new JvmWideVariable<>(my.package.Counter.class, "COUNT", Integer.class, 0);
 *     public static void increaseCounter() {
 *       COUNT.executeRunnableSynchronously(() -> {
 *         COUNT.set(COUNT.get() + 1);
 *       });
 *     }
 * }
 * }</pre>
 *
 * <p>JVM-wide variables either can be kept alive for the entire JVM lifetime, or can be released at
 * a certain point (e.g., at the end of a test method). Releasing a JVM-wide variable requires: (1)
 * un-registering the variable from the JVM via the {@link #unregister()} method, and (2) un-linking
 * all references to the {@code JvmWideVariable} instance that accesses it.
 *
 * <p>This class is thread-safe.
 *
 * @param <T> The type of the JVM-wide variable. Must be loaded once by a single class loader.
 */
public final class JvmWideVariable<T> {

    /** The pattern for checking the validity of the variable's full name. */
    @NonNull private static final Pattern VALID_NAME_PATTERN = Pattern.compile("[^\\s:=,]+");

    /**
     * The JVM-wide variable table, which is a map from the variable's full name to the actual
     * JVM-wide variable (an {@link AtomicReference} holding the variable's value).
     */
    @NonNull
    private static final ConcurrentMap<String, AtomicReference<Object>> variableTable =
            createVariableTableIfNotExists();

    /** The full name of the JVM-wide variable. */
    @NonNull private final String fullName;

    /** Whether this variable has been unregistered. */
    private boolean unregistered;



    /**
     * Creates a {@code JvmWideVariable} instance that can access a JVM-wide variable. If the
     * JVM-wide variable does not yet exist, this constructor creates the variable and initializes
     * it with an initial value.
     *
     * <p>A JVM-wide variable is uniquely defined by its group, name, and tag. Typically, a JVM-wide
     * variable should be assigned to a static field of a class. In that case, the group of the
     * variable is usually the fully qualified name of the defining class of the static field. The
     * tag of the variable is used to separate variables which have the exact same group and name
     * but should not be shared (e.g., if the JVM loads different versions of the code and the types
     * of the variables have changed across those versions).
     *
     * <p>A JVM-wide variable has a type and an initial value. The type {@code T} of a JVM-wide
     * variable must be loaded once by a single class loader, to avoid runtime casting exceptions.
     * Currently, this constructor requires that single class loader to be the bootstrap class
     * loader.
     *
     * <p>If the users of this class provide a different type for a variable that already exists, it
     * will also result in runtime casting exceptions. However, if they provide a different initial
     * value for a variable that already exists, this constructor will simply ignore that value.
     *
     * <p>The users need to explicitly pass type {@code T} via a {@link TypeToken} or a {@link
     * Class} instance. This constructor takes a ({@code TypeToken} as it is more general (it can
     * capture complex types such as {@code Map<K, V>}). If the type is simple (can be represented
     * fully by a {@code Class} instance), the users can use some other constructor instead.
     *
     * @param group the group of the variable
     * @param name the name of the variable
     * @param tag the tag of the variable
     * @param typeToken the type of the variable, which must be loaded by the bootstrap class loader
     * @param initialValueSupplier the supplier that produces the initial value of the variable. It
     *     is called only when the variable is first created. The supplied value can be null.
     */
    public JvmWideVariable(
            @NonNull String group,
            @NonNull String name,
            @NonNull String tag,
            @NonNull TypeToken<T> typeToken,
            @NonNull Supplier<T> initialValueSupplier) {
        String fullName = getFullName(group, name, tag);
        verifyBootstrapLoadedType(typeToken.getType(), fullName);

        this.fullName = fullName;
        this.unregistered = false;

        variableTable.computeIfAbsent(
                fullName, (any) -> new AtomicReference<>(initialValueSupplier.get()));
    }

    /**
     * Creates a {@code JvmWideVariable} instance that can access a JVM-wide variable, similar to
     * {@link #JvmWideVariable(String, String, String, TypeToken, Supplier)}. See the javadoc of
     * that constructor for more details.
     *
     * <p>This constructor can be used when:
     *
     * <ol>
     *   <li>The group of the variable is the fully qualified name of the class in which this {@code
     *       JvmWideVariable} instance is defined.
     *   <li>The tag of the variable is the variable's type. This is so that if the type of the
     *       variable has changed, it will be considered a new variable and won't conflict with the
     *       previous variable.
     *   <li>The type of the variable is a simple type represented by a {@link Class} instance
     *       instead of a {@link TypeToken} instance.
     *   <li>The initial value of the variable is provided directly instead of using a supplier.
     * </ol>
     *
     * <p>IMPORTANT: This constructor should be used only when the value of the variable has the
     * exact type {@code T}, not a sub-type of {@code T}; otherwise, type {@code T} alone would not
     * be sufficient to serve as a tag to capture the "uniqueness" of the variable.
     *
     * @param definingClass the class in which this {@code JvmWideVariable} instance is defined
     * @param name the name of the variable
     * @param type the type of the variable, which must be loaded by the bootstrap class loader
     * @param initialValue the initial value of the variable, can be null.
     * @see #JvmWideVariable(String, String, String, TypeToken, Supplier)
     */
    public JvmWideVariable(
            @NonNull Class<?> definingClass,
            @NonNull String name,
            @NonNull Class<T> type,
            @Nullable T initialValue) {
        this(definingClass.getName(), name, type.getName(), TypeToken.of(type), () -> initialValue);
    }

    /**
     * Creates a {@code JvmWideVariable} instance that can access a JVM-wide variable, similar to
     * {@link #JvmWideVariable(String, String, String, TypeToken, Supplier)}. See the javadoc of
     * that constructor for more details.
     *
     * <p>This constructor can be used when:
     *
     * <ol>
     *   <li>The group of the variable is the fully qualified name of the class in which this {@code
     *       JvmWideVariable} instance is defined.
     *   <li>The tag of the variable is the variable's type. This is so that if the type of the
     *       variable has changed, it will be considered a new variable and won't conflict with the
     *       previous variable.
     * </ol>
     *
     * <p>IMPORTANT: This constructor should be used only when the value of the variable has the
     * exact type {@code T}, not a sub-type of {@code T}; otherwise, type {@code T} alone would not
     * be sufficient to serve as a tag to capture the "uniqueness" of the variable.
     *
     * @param definingClass the class in which this {@code JvmWideVariable} instance is defined
     * @param name the name of the variable
     * @param typeToken the type of the variable, which must be loaded by the bootstrap class loader
     * @param initialValueSupplier the supplier that produces the initial value of the variable. It
     *     is called only when the variable is first created. The supplied value can be null.
     * @see #JvmWideVariable(String, String, String, TypeToken, Supplier)
     */
    public JvmWideVariable(
            @NonNull Class<?> definingClass,
            @NonNull String name,
            @NonNull TypeToken<T> typeToken,
            @NonNull Supplier<T> initialValueSupplier) {
        this(
                definingClass.getName(),
                name,
                collectComponentClasses(typeToken.getType())
                        .stream()
                        .map(Class::getName)
                        .collect(Collectors.joining("-")),
                typeToken,
                initialValueSupplier);
    }

    /**
     * Creates a {@code JvmWideVariable} instance that can access a JVM-wide variable, similar to
     * {@link #JvmWideVariable(String, String, String, TypeToken, Supplier)}. See the javadoc of
     * that constructor for more details.
     *
     * <p>This constructor can be used when:
     *
     * <ul>
     *   <li>The tag of the variable is the variable's type. This is so that if the type of the
     *       variable has changed, it will be considered a new variable and won't conflict with the
     *       previous variable.
     * </ul>
     *
     * <p>IMPORTANT: This constructor should be used only when the value of the variable has the
     * exact type {@code T}, not a sub-type of {@code T}; otherwise, type {@code T} alone would not
     * be sufficient to serve as a tag to capture the "uniqueness" of the variable.
     *
     * @param group the group of the variable
     * @param name the name of the variable
     * @param typeToken the type of the variable, which must be loaded by the bootstrap class loader
     * @param initialValueSupplier the supplier that produces the initial value of the variable. It
     *     is called only when the variable is first created. The supplied value can be null.
     * @see #JvmWideVariable(String, String, String, TypeToken, Supplier)
     */
    public JvmWideVariable(
            @NonNull String group,
            @NonNull String name,
            @NonNull TypeToken<T> typeToken,
            @NonNull Supplier<T> initialValueSupplier) {
        this(
                group,
                name,
                collectComponentClasses(typeToken.getType())
                        .stream()
                        .map(Class::getName)
                        .collect(Collectors.joining("-")),
                typeToken,
                initialValueSupplier);
    }

    /** Returns the full name of a JVM-wide variable given its group, name, and tag. */
    @VisibleForTesting
    @NonNull
    static String getFullName(@NonNull String group, @NonNull String name, @NonNull String tag) {
        Preconditions.checkArgument(VALID_NAME_PATTERN.matcher(group).matches());
        Preconditions.checkArgument(VALID_NAME_PATTERN.matcher(name).matches());
        Preconditions.checkArgument(VALID_NAME_PATTERN.matcher(tag).matches());

        return group + ":name=" + name + ",tag=" + tag;
    }

    /**
     * Collects all classes that are involved in defining the given type and check that they are all
     * loaded by the bootstrap class loader.
     */
    private static void verifyBootstrapLoadedType(@NonNull Type type, @NonNull String variable) {
        for (Class<?> clazz : collectComponentClasses(type)) {
            // CodeAssist changed: BootstrapClassLoader is never null
            // it is instead an instance of java.lang.BootClassLoader
            Verify.verify(
                    clazz.getClassLoader().getClass().getName().startsWith("java.lang.BootClassLoader"),
                    "Type %s used to define JVM-wide variable %s must be loaded"
                            + " by the bootstrap class loader but is loaded by %s",
                    clazz,
                    variable,
                    clazz.getClassLoader());
        }
    }

    /** Returns all classes that are involved in defining the given type. */
    @VisibleForTesting
    static Collection<Class<?>> collectComponentClasses(@NonNull Type type) {
        ImmutableSet.Builder<Class<?>> builder = ImmutableSet.builder();
        doCollectComponentClasses(type, builder);
        return builder.build();
    }

    private static void doCollectComponentClasses(
            @NonNull Type type, @NonNull ImmutableSet.Builder<Class<?>> builder) {
        if (type instanceof Class<?>) {
            builder.add((Class<?>) type);
        } else if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;
            doCollectComponentClasses(parameterizedType.getRawType(), builder);
            if (parameterizedType.getOwnerType() != null) {
                doCollectComponentClasses(parameterizedType.getOwnerType(), builder);
            }
            for (Type componentType : parameterizedType.getActualTypeArguments()) {
                doCollectComponentClasses(componentType, builder);
            }
        } else if (type instanceof GenericArrayType) {
            doCollectComponentClasses(((GenericArrayType) type).getGenericComponentType(), builder);
        } else if (type instanceof WildcardType) {
            WildcardType wildcardType = (WildcardType) type;
            for (Type componentType : wildcardType.getLowerBounds()) {
                doCollectComponentClasses(componentType, builder);
            }
            for (Type componentType : wildcardType.getUpperBounds()) {
                doCollectComponentClasses(componentType, builder);
            }
        } else {
            throw new IllegalArgumentException("Type " + type + " is not yet supported");
        }
    }

    /** Creates the JVM-wide variable table if it does not yet exist. */
    @NonNull
    private static ConcurrentMap<String, AtomicReference<Object>> createVariableTableIfNotExists() {
        // The MBeanServer below is a JVM-wide singleton object (it is the same instance even when
        // accessed from different class loaders). We use it to store the JVM-wide variable table.
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();

        // Similar to a JVM-wide variable, the variable table has a name, type, and tag. We use
        // a tag so that if the implementation of this class changes in a way that this variable
        // table is no longer compatible with prior versions (e.g., if its type has changed), we
        // can update a unique tag to avoid conflicts across versions. Currently, we use the type of
        // the variable table as its tag.
        Type type = new TypeToken<ConcurrentMap<String, AtomicReference<Object>>>() {}.getType();
        String tag =
                collectComponentClasses(type)
                        .stream()
                        .map(Class::getName)
                        .collect(Collectors.joining("-"));

        ObjectName objectName;
        try {
            objectName = new ObjectName(getFullName("JvmWideVariable", "variableTable", tag));
        } catch (MalformedObjectNameException e) {
            throw new RuntimeException(e);
        }

        //noinspection SynchronizationOnLocalVariableOrMethodParameter
        synchronized (server) {
            if (!server.isRegistered(objectName)) {
                ValueWrapper<ConcurrentMap<String, AtomicReference<Object>>> valueWrapper =
                        new ValueWrapper<>(
                                new ConcurrentHashMap<String, AtomicReference<Object>>());
                try {
                    server.registerMBean(valueWrapper, objectName);
                } catch (InstanceAlreadyExistsException
                        | MBeanRegistrationException
                        | NotCompliantMBeanException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        ConcurrentMap<String, AtomicReference<Object>> variableTable;
        try {
            //noinspection unchecked
            variableTable =
                    (ConcurrentMap<String, AtomicReference<Object>>)
                            server.getAttribute(objectName, ValueWrapperMBean.VALUE_PROPERTY);
        } catch (MBeanException
                | AttributeNotFoundException
                | ReflectionException
                | InstanceNotFoundException e) {
            throw new RuntimeException(e);
        }
        return Verify.verifyNotNull(variableTable);
    }

    /**
     * Returns the actual JVM-wide variable (an {@link AtomicReference} holding the variable's
     * value).
     *
     * @throws VerifyException if the variable has already been unregistered
     */
    @NonNull
    private AtomicReference<T> getVariable() {
        Verify.verify(
                !unregistered,
                "This JwmWideVariable instance was used to access JVM-wide variable %s,"
                        + " but has already been unregistered",
                fullName);
        //noinspection unchecked
        return Verify.verifyNotNull(
                (AtomicReference<T>) variableTable.get(fullName),
                "JVM-wide variable %s has already been unregistered",
                fullName);
    }

    /** Returns the current value of this JVM-wide variable. */
    @Nullable
    public T get() {
        return getVariable().get();
    }

    /**
     * Sets a value to this JVM-wide variable.
     */
    public void set(@Nullable T value) {
        getVariable().set(value);
    }

    /**
     * Executes the given action, where the execution is synchronized on the JVM-wide variable (not
     * the variable's value).
     *
     * <p>This method is used to replace a static synchronized method operating on a static variable
     * when converting the static variable into a JVM-wide variable. (See the javadoc of {@link
     * JvmWideVariable}.)
     *
     * @throws ExecutionException if an exception occurred during the execution of the action
     */
    @Nullable
    public <V> V executeCallableSynchronously(@NonNull Callable<V> action)
            throws ExecutionException {
        synchronized (getVariable()) {
            try {
                return action.call();
            } catch (Exception e) {
                throw new ExecutionException(e);
            }
        }
    }

    /**
     * Executes the given action, where the execution is synchronized on the JVM-wide variable.
     *
     * @see #executeCallableSynchronously(Callable)
     */
    @SuppressWarnings("UnusedReturnValue")
    @Nullable
    public <V> V executeSupplierSynchronously(@NonNull Supplier<V> action) {
        try {
            return executeCallableSynchronously(action::get);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Executes the given action, where the execution is synchronized on the JVM-wide variable.
     *
     * @see #executeCallableSynchronously(Callable)
     */
    @SuppressWarnings("unused")
    public void executeRunnableSynchronously(@NonNull Runnable action) {
        executeSupplierSynchronously(
                () -> {
                    action.run();
                    return null;
                });
    }

    /**
     * Unregisters the JVM-wide variable from the JVM.
     *
     * <p>Releasing a JVM-wide variable requires: (1) un-registering the variable from the JVM via
     * this method, and (2) un-linking all references to the {@code JvmWideVariable} instance that
     * accesses it. Therefore, the users of this method typically need to also perform step (2) to
     * completely release the variable.
     *
     * <p>THREAD SAFETY: This method must be called only when the variable is not in use by any
     * threads (e.g., at the end of a test method, when no other test is running). Otherwise, it
     * would break the thread safety of this class.
     */
    public void unregister() {
        Verify.verify(
                !unregistered,
                "This JwmWideVariable instance was used to access JVM-wide variable %s,"
                        + " but has already been unregistered",
                fullName);
        Verify.verifyNotNull(
                variableTable.remove(fullName),
                "JVM-wide variable %s has already been unregistered",
                fullName);
        unregistered = true;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("fullName", fullName)
                .add("unregistered", unregistered)
                .toString();
    }

    /**
     * Returns a unique JVM-wide object per given key. (Two keys are the same if one equals() the
     * other.)
     *
     * <p>If this method is called on a new key, it will create and return a new object. If this
     * method is called on an existing key, it will return the previously created object.
     *
     * <p>The key consists of the following:
     *
     * <ol>
     *   <li>The name of the class where the object is used. Note that we use the class name, not
     *       the class instance, so that the key is shared across class loaders.
     *   <li>The name of the variable that the object is assigned to
     *   <li>The type of the user-specified key
     *   <li>The type of the returned object
     *   <li>The user-specified key
     * </ol>
     *
     * @param definingClass the class where the object is used
     * @param variableName the name of the variable that the object is assigned to
     * @param keyType the type of the user-specified key, which must be loaded by the bootstrap
     *     class loader
     * @param valueType the type of the returned object, which must be loaded by the bootstrap class
     *     loader
     * @param key the user-specified key
     * @param newObjectSupplier the supplier that produces a new object for a new key. It is called
     *     only when the key is new. The supplied value must not be null.
     */
    @NonNull
    public static <K, V> V getJvmWideObjectPerKey(
            @NonNull Class<?> definingClass,
            @NonNull String variableName,
            @NonNull TypeToken<K> keyType,
            @NonNull TypeToken<V> valueType,
            @NonNull K key,
            @NonNull Supplier<V> newObjectSupplier) {
        ConcurrentMap<K, V> keyToObjectMap =
                Verify.verifyNotNull(
                        new JvmWideVariable<>(
                                        definingClass,
                                        variableName,
                                        new TypeToken<ConcurrentMap<K, V>>() {}.where(
                                                        new TypeParameter<K>() {}, keyType)
                                                .where(new TypeParameter<V>() {}, valueType),
                                        ConcurrentHashMap::new)
                                .get());

        return keyToObjectMap.computeIfAbsent(
                key, (any) -> Verify.verifyNotNull(newObjectSupplier.get()));
    }

    /** The MBean object, as required by a standard MBean implementation. */
    private static final class ValueWrapper<T> implements ValueWrapperMBean<T> {

        @Nullable private final T value;

        public ValueWrapper(@Nullable T value) {
            this.value = value;
        }

        @Nullable
        @Override
        public T getValue() {
            return this.value;
        }
    }

    /** The MBean interface, as required by a standard MBean implementation. */
    public interface ValueWrapperMBean<T> {

        @NonNull String VALUE_PROPERTY = "Value";

        @Nullable T getValue();
    }
}
