package javax.management;

public class OperationsException extends JMException {
    private static final long serialVersionUID = -4967597595580536216L;

    public OperationsException() {
    }

    public OperationsException(String message) {
        super(message);
    }
}
