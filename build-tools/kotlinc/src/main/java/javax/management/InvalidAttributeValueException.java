package javax.management;

public class InvalidAttributeValueException extends OperationsException {
    private static final long serialVersionUID = 2164571879317142449L;

    public InvalidAttributeValueException() {
    }

    public InvalidAttributeValueException(String message) {
        super(message);
    }
}
