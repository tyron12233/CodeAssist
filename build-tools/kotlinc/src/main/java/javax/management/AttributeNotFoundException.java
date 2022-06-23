package javax.management;

public class AttributeNotFoundException extends OperationsException {
    private static final long serialVersionUID = 6511584241791106926L;

    public AttributeNotFoundException() {
    }

    public AttributeNotFoundException(String message) {
        super(message);
    }
}
