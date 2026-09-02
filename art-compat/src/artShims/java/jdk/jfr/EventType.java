package jdk.jfr;
/** ART stub — JFR absent on Android. */
public final class EventType {
    private EventType() {}
    private static final EventType INSTANCE = new EventType();
    public static EventType getEventType(Class<? extends Event> eventClass) { return INSTANCE; }
    public boolean isEnabled() { return false; }
    public String getName() { return ""; }
    public String getLabel() { return ""; }
    public long getId() { return 0L; }
}
