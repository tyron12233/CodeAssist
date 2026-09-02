package jdk.jfr;

/**
 * ART stub — Java Flight Recorder (jdk.jfr) is absent on Android. Some FIR components define JFR events
 * (subclasses of this) for resolution tracing. Headless no-op: events are never enabled, commit does nothing.
 */
public abstract class Event {
    public void begin() {}
    public void end() {}
    public void commit() {}
    public boolean isEnabled() { return false; }
    public boolean shouldCommit() { return false; }
    public void set(int index, Object value) {}
}
