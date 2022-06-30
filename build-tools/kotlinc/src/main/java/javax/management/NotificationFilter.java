package javax.management;

import java.io.Serializable;

public interface NotificationFilter extends Serializable {
    boolean isNotificationEnabled(Notification var1);
}
