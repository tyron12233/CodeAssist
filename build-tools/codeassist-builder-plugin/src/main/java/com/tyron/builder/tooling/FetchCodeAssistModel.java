package com.tyron.builder.tooling;

import org.gradle.tooling.BuildAction;
import org.gradle.tooling.BuildController;

interface Model {

}
public class FetchCodeAssistModel implements BuildAction<Model> {
    @Override
    public Model execute(BuildController controller) {
        return controller.getModel(Model.class);
    }
}
