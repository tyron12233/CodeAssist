package com.tyron.builder.api.internal.plugins;

import com.tyron.builder.api.Plugin;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.model.internal.inspect.ModelRuleSourceDetector;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
public class PluginInspector {

    private final ModelRuleSourceDetector modelRuleSourceDetector;

    public PluginInspector(ModelRuleSourceDetector modelRuleSourceDetector) {
        this.modelRuleSourceDetector = modelRuleSourceDetector;
    }

    public <T> PotentialPlugin<T> inspect(Class<T> type) {
        boolean implementsInterface = Plugin.class.isAssignableFrom(type);
        boolean hasRules = this.modelRuleSourceDetector.hasRules(type);

        if (implementsInterface) {
            @SuppressWarnings("unchecked") Class<? extends Plugin<?>> cast = (Class<? extends Plugin<?>>) type;
            return Cast.uncheckedCast(toImperative(cast, hasRules));
        } else if (hasRules) {
            return new PotentialPureRuleSourceClassPlugin<T>(type);
        } else {
            return new PotentialUnknownTypePlugin<T>(type);
        }
    }

    private <T extends Plugin<?>> PotentialPlugin<T> toImperative(Class<T> type, boolean hasRules) {
        if (hasRules) {
            return new PotentialHybridImperativeAndRulesPlugin<T>(type);
        } else {
            return new PotentialImperativeClassPlugin<T>(type);
        }
    }

    private static class PotentialImperativeClassPlugin<T extends Plugin<?>> implements PotentialPlugin<T> {

        private final Class<T> clazz;

        public PotentialImperativeClassPlugin(Class<T> clazz) {
            this.clazz = clazz;
        }

        @Override
        public Class<T> asClass() {
            return clazz;
        }

        @Override
        public boolean isImperative() {
            return true;
        }

        @Override
        public Type getType() {
            return Type.IMPERATIVE_CLASS;
        }

        @Override
        public boolean isHasRules() {
            return false;
        }
    }

    private static class PotentialHybridImperativeAndRulesPlugin<T extends Plugin<?>> implements PotentialPlugin<T> {

        private final Class<T> clazz;

        public PotentialHybridImperativeAndRulesPlugin(Class<T> clazz) {
            this.clazz = clazz;
        }

        @Override
        public Class<T> asClass() {
            return clazz;
        }

        @Override
        public boolean isImperative() {
            return true;
        }

        @Override
        public boolean isHasRules() {
            return true;
        }

        @Override
        public Type getType() {
            return Type.HYBRID_IMPERATIVE_AND_RULES_CLASS;
        }

    }

    private static class PotentialPureRuleSourceClassPlugin<T> implements PotentialPlugin<T> {

        private final Class<T> clazz;

        public PotentialPureRuleSourceClassPlugin(Class<T> clazz) {
            this.clazz = clazz;
        }

        @Override
        public Class<T> asClass() {
            return clazz;
        }

        @Override
        public boolean isImperative() {
            return false;
        }

        @Override
        public Type getType() {
            return Type.PURE_RULE_SOURCE_CLASS;
        }

        @Override
        public boolean isHasRules() {
            return false;
        }
    }

    private static class PotentialUnknownTypePlugin<T> implements PotentialPlugin<T> {

        private final Class<T> clazz;

        public PotentialUnknownTypePlugin(Class<T> clazz) {
            this.clazz = clazz;
        }

        @Override
        public Class<T> asClass() {
            return clazz;
        }

        @Override
        public boolean isImperative() {
            return false;
        }

        @Override
        public boolean isHasRules() {
            return false;
        }

        @Override
        public Type getType() {
            return Type.UNKNOWN;
        }

    }
}
