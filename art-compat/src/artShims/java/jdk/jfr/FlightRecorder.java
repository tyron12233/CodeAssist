package jdk.jfr;
/** ART stub — JFR absent on Android. */
public final class FlightRecorder {
    private FlightRecorder() {}
    public static void register(Class<? extends Event> eventClass) {}
    public static void unregister(Class<? extends Event> eventClass) {}
    public static boolean isAvailable() { return false; }
    public static boolean isInitialized() { return false; }
    public static void addPeriodicEvent(Class<? extends Event> eventClass, Runnable hook) {}
    public static boolean removePeriodicEvent(Runnable hook) { return false; }
}
