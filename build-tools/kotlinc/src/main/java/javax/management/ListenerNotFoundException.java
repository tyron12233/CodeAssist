package javax.management;

public class ListenerNotFoundException extends OperationsException {
    private static final long serialVersionUID = -7242605822448519061L;

    public ListenerNotFoundException() {
    }

    public ListenerNotFoundException(String message) {
        super(message);
    }
}
