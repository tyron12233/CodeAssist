package org.gradle.api.internal.project.taskfactory;

import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.internal.reflect.Instantiator;

public interface TaskActionFactory  {
    Action<? super Task> create(Instantiator instantiator);
}
