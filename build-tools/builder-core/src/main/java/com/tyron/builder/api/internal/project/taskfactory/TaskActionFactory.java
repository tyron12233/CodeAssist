package com.tyron.builder.api.internal.project.taskfactory;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Task;
import com.tyron.builder.internal.reflect.Instantiator;

public interface TaskActionFactory  {
    Action<? super Task> create(Instantiator instantiator);
}
