package org.jetbrains.kotlin.com.intellij.util.indexing;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.ide.plugins.PluginUtil;
import org.jetbrains.kotlin.com.intellij.openapi.application.PathManager;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.PluginId;
import org.jetbrains.kotlin.com.intellij.util.io.SimpleStringPersistentEnumerator;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Eugene Zhuravlev
 */
public class ID<K, V> extends IndexId<K,V> {
  private static final Logger LOG = Logger.getInstance(ID.class);

  private static volatile SimpleStringPersistentEnumerator ourNameToIdRegistry = new SimpleStringPersistentEnumerator(getEnumFile());

  private final static Map<String, ID<?, ?>> ourIdObjects = new ConcurrentHashMap<>();

  private static final Map<ID<?, ?>, PluginId> ourIdToPluginId = Collections.synchronizedMap(new HashMap<>());
  private static final Map<ID<?, ?>, Throwable> ourIdToRegistrationStackTrace = Collections.synchronizedMap(new HashMap<>());
  static final int MAX_NUMBER_OF_INDICES = Short.MAX_VALUE;

  private volatile int myUniqueId;

  @ApiStatus.Internal
  @NotNull
  private static Path getEnumFile() {
    return PathManager.getIndexRoot().toPath().resolve("indices.enum");
  }

  @ApiStatus.Internal
  public static void reloadEnumFile() {
    reloadEnumFile(getEnumFile());
  }

  //RC: method should probably be synchronized, since it uses current value .ourNameToIdRegistry
  //    while building a new state, and this is unsafe if whole method could be called concurrently,
  //    so that old value could be changed along the way. Right now this is 'safe' since method is
  //    called only while shared index initialization, but...
  private static void reloadEnumFile(@NotNull Path enumFile) {
    if (enumFile.equals(ourNameToIdRegistry.getFile())) {
      return;
    }

    SimpleStringPersistentEnumerator newNameToIdRegistry = new SimpleStringPersistentEnumerator(getEnumFile());
    Map<String, Integer> newInvertedState = newNameToIdRegistry.getInvertedState();
    Map<String, Integer> oldInvertedState = ourNameToIdRegistry.getInvertedState();

    oldInvertedState.forEach((oldKey, oldId) -> {
      Integer newId = newInvertedState.get(oldKey);

      if (newId == null) {
        int createdId = newNameToIdRegistry.enumerate(oldKey);
        if (createdId != oldId) {
          reassign(oldKey, createdId);
        }
      }
      else if (oldId.intValue() != newId.intValue()) {
        reassign(oldKey, newId);
      }
    });

    ourNameToIdRegistry = newNameToIdRegistry;
  }

  private static void reassign(String name, int newId) {
    ID<?, ?> id = ourIdObjects.get(name);
    if (id != null) {
      id.myUniqueId = newId;
    }
  }

  @ApiStatus.Internal
  protected ID(@NotNull String name, @Nullable PluginId pluginId) {
    super(name);
    myUniqueId = stringToId(name);

    ID<?,?> old = ourIdObjects.put(name, this);
    assert old == null : "ID with name '" + name + "' is already registered";

    PluginId oldPluginId = ourIdToPluginId.put(this, pluginId);
    assert oldPluginId == null : "ID with name '" + name + "' is already registered in " + oldPluginId + " but current caller is " + pluginId;

    ourIdToRegistrationStackTrace.put(this, new Throwable());
  }

  private static int stringToId(@NotNull String name) {
    int id = ourNameToIdRegistry.enumerate(name);
    if (id != (short)id) {
      throw new AssertionError("Too many indexes registered");
    }
    return id;
  }

  static void reinitializeDiskStorage() {
    ourNameToIdRegistry.forceDiskSync();
  }

  @NotNull
  public static synchronized <K, V> ID<K, V> create(@NonNls @NotNull String name) {
    PluginId pluginId = getCallerPluginId();
    final ID<K, V> found = findByName(name, true, pluginId);
    return found == null ? new ID<>(name, pluginId) : found;
  }

  @Nullable
  public static <K, V> ID<K, V> findByName(@NotNull String name) {
    return findByName(name, false, null);
  }

  @ApiStatus.Internal
  @Nullable
  protected static <K, V> ID<K, V> findByName(@NotNull String name,
                                              boolean checkCallerPlugin,
                                              @Nullable PluginId requiredPluginId) {
    //noinspection unchecked
    ID<K, V> id = (ID<K, V>)findById(stringToId(name));
    if (checkCallerPlugin && id != null) {
      PluginId actualPluginId = ourIdToPluginId.get(id);

      String actualPluginIdStr = actualPluginId == null ? "IJ Core" : actualPluginId.getIdString();
      String requiredPluginIdStr = requiredPluginId == null ? "IJ Core" : requiredPluginId.getIdString();

      if (!Objects.equals(actualPluginIdStr, requiredPluginIdStr)) {
        Throwable registrationStackTrace = ourIdToRegistrationStackTrace.get(id);
        String message = getInvalidIdAccessMessage(name, actualPluginIdStr, requiredPluginIdStr, registrationStackTrace);
        if (registrationStackTrace != null) {
          throw new AssertionError(message, registrationStackTrace);
        } else {
          throw new AssertionError(message);
        }
      }
    }
    return id;
  }

  @NotNull
  private static String getInvalidIdAccessMessage(@NotNull String name,
                                                  @Nullable String actualPluginIdStr,
                                                  @Nullable String requiredPluginIdStr,
                                                  @Nullable Throwable registrationStackTrace) {
    return "ID with name '" + name +
           "' requested for plugin " + requiredPluginIdStr +
           " but registered for " + actualPluginIdStr + " plugin. " +
           "Please use an instance field to access corresponding ID." +
           (registrationStackTrace == null ? " Registration stack trace: " : "");
  }

  @ApiStatus.Internal
  public static Collection<ID<?, ?>> getRegisteredIds() {
    synchronized (ourIdToPluginId) {
      return Collections.unmodifiableSet(new HashSet<>(ourIdToPluginId.keySet()));
    }
  }

  @ApiStatus.Internal
  @NotNull
  public Throwable getRegistrationTrace() {
    return ourIdToRegistrationStackTrace.get(this);
  }

  @ApiStatus.Internal
  public int getUniqueId() {
    return myUniqueId;
  }

  @ApiStatus.Internal
  @Nullable
  public PluginId getPluginId() {
    return ourIdToPluginId.get(this);
  }

  @ApiStatus.Internal
  public static ID<?, ?> findById(int id) {
    String key = ourNameToIdRegistry.valueOf(id);
    return key == null ? null : ourIdObjects.get(key);
  }

  @ApiStatus.Internal
  @Nullable
  protected static PluginId getCallerPluginId() {
    if (PluginUtil.getInstance() == null) {
      return null;
    }
    return PluginUtil.getInstance().getCallerPlugin(4);
  }

  @ApiStatus.Internal
  public synchronized static void unloadId(@NotNull ID<?, ?> id) {
    String name = id.getName();
    ID<?, ?> oldID = ourIdObjects.remove(name);
    LOG.assertTrue(id.equals(oldID), "Failed to unload: " + name);
    ourIdToPluginId.remove(id);
    ourIdToRegistrationStackTrace.remove(id);
  }
}