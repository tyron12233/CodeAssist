package com.tyron.builder.api.internal.tasks.properties;


public enum InputFilePropertyType {
    FILE(ValidationActions.INPUT_FILE_VALIDATOR),
    DIRECTORY(ValidationActions.INPUT_DIRECTORY_VALIDATOR),
    FILES(ValidationActions.NO_OP);

    private final ValidationAction validationAction;

    InputFilePropertyType(ValidationAction validationAction) {
        this.validationAction = validationAction;
    }

    public ValidationAction getValidationAction() {
        return validationAction;
    }
}