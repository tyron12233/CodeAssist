package com.tyron.builder.internal.actor;

public interface ActorFactory {
    /**
     * Creates an asynchronous actor for the given target object.
     *
     * @param target The target object.
     * @return The actor.
     */
    Actor createActor(Object target);

    /**
     * Creates a synchronous actor for the given target object.
     *
     * @param target The target object.
     * @return The actor.
     */
    Actor createBlockingActor(Object target);
}
