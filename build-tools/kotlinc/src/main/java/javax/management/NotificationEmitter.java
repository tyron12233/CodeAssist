package javax.management;

public interface NotificationEmitter extends NotificationBroadcaster {
    void removeNotificationListener(NotificationListener var1, NotificationFilter var2, Object var3) throws ListenerNotFoundException;
}
