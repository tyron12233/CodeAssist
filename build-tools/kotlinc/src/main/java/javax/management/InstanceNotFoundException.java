package javax.management;

public class InstanceNotFoundException extends OperationsException {
    private static final long serialVersionUID = -882579438394773049L;

    public InstanceNotFoundException() {
    }

    public InstanceNotFoundException(String message) {
        super(message);
    }
}
