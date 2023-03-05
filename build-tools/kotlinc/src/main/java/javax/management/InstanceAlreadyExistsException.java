package javax.management;

public class InstanceAlreadyExistsException extends OperationsException {
    private static final long serialVersionUID = 8893743928912733931L;

    public InstanceAlreadyExistsException() {
    }

    public InstanceAlreadyExistsException(String message) {
        super(message);
    }
}
