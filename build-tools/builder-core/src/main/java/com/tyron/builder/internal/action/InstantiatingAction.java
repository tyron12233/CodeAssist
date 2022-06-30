package com.tyron.builder.internal.action;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.ReusableAction;
import com.tyron.builder.internal.Actions;
import com.tyron.builder.internal.reflect.Instantiator;

import java.util.List;

public class InstantiatingAction<DETAILS> implements Action<DETAILS> {
    private final ConfigurableRules<DETAILS> rules;
    private final Instantiator instantiator;
    private final ExceptionHandler<DETAILS> exceptionHandler;
    private final Action<DETAILS>[] reusableRules;

    @SuppressWarnings("unchecked")
    public InstantiatingAction(ConfigurableRules<DETAILS> rules, Instantiator instantiator, ExceptionHandler<DETAILS> exceptionHandler) {
        this.rules = rules;
        this.instantiator = instantiator;
        this.exceptionHandler = exceptionHandler;
        this.reusableRules = new Action[rules.getConfigurableRules().size()];
    }

    public InstantiatingAction<DETAILS> withInstantiator(Instantiator instantiator) {
        return new InstantiatingAction<DETAILS>(rules, instantiator, exceptionHandler);
    }

    @Override
    public void execute(DETAILS target) {
        List<ConfigurableRule<DETAILS>> configurableRules = rules.getConfigurableRules();
        int i = 0;
        for (ConfigurableRule<DETAILS> rule : configurableRules) {
            try {
                Action<DETAILS> instance = reusableRules[i];
                if (!(instance instanceof ReusableAction)) {
                    instance = instantiator.newInstance(rule.getRuleClass(), rule.getRuleParams().isolate());
                    // This second check is only done so that we can make the difference between an uninitialized rule (never seen before) and
                    // a rule which is not reusable
                    if (instance instanceof ReusableAction) {
                        reusableRules[i] = instance;
                    } else {
                        reusableRules[i] = Actions.doNothing();
                    }
                }
                instance.execute(target);
            } catch (Throwable t) {
                exceptionHandler.handleException(target, t);
            }
            i++;
        }
    }

    public ConfigurableRules<DETAILS> getRules() {
        return rules;
    }

    public Instantiator getInstantiator() {
        return instantiator;
    }

    public interface ExceptionHandler<U> {
        void handleException(U target, Throwable throwable);
    }
}
