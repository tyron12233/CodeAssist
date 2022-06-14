package com.tyron.builder.model.internal.core;

/**
 * A hard-coded sequence of model actions that can be applied to a model element.
 *
 * <p>This is pretty much a placeholder for something more descriptive.
 */
public enum ModelActionRole {
    Discover(ModelNode.State.Discovered, false), // Defines all projections for the node
    Create(ModelNode.State.Created, false), // Initializes the node
    Defaults(ModelNode.State.DefaultsApplied, true), // Allows a mutation to setup default values for an element
    Initialize(ModelNode.State.Initialized, true), // Mutation action provided when an element is defined
    Mutate(ModelNode.State.Mutated, true), // Customisations
    Finalize(ModelNode.State.Finalized, true), // Post customisation default values
    Validate(ModelNode.State.SelfClosed, true); // Post mutation validations

    private final ModelNode.State target;
    private final boolean subjectViewAvailable;

    ModelActionRole(ModelNode.State target, boolean subjectViewAvailable) {
        this.target = target;
        this.subjectViewAvailable = subjectViewAvailable;
    }

    public ModelNode.State getTargetState() {
        return target;
    }

    /**
     * Returns whether the private data of the subject node can be viewed as a Java object by a rule in this role.
     */
    public boolean isSubjectViewAvailable() {
        return subjectViewAvailable;
    }
}
