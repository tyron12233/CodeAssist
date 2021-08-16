package com.sun.tools.javac.util;

import java.util.EnumSet;

/**
 * Configuration object provided by the rich formatter.
 */
public class RichConfiguration extends ForwardingConfiguration {

    /**
     * set of enabled rich formatter's features
     */
    protected EnumSet<RichFormatterFeature> features;

    @SuppressWarnings("fallthrough")
    public RichConfiguration(Options options, AbstractDiagnosticFormatter formatter) {
        super(formatter.getConfiguration());
        features = formatter.isRaw() ? EnumSet.noneOf(RichFormatterFeature.class) :
                EnumSet.of(RichFormatterFeature.SIMPLE_NAMES,
                        RichFormatterFeature.WHERE_CLAUSES,
                        RichFormatterFeature.UNIQUE_TYPEVAR_NAMES);
        String diagOpts = options.get("diags");
        if (diagOpts != null) {
            for (String args : diagOpts.split(",")) {
                if (args.equals("-where")) {
                    features.remove(RichFormatterFeature.WHERE_CLAUSES);
                } else if (args.equals("where")) {
                    features.add(RichFormatterFeature.WHERE_CLAUSES);
                }
                if (args.equals("-simpleNames")) {
                    features.remove(RichFormatterFeature.SIMPLE_NAMES);
                } else if (args.equals("simpleNames")) {
                    features.add(RichFormatterFeature.SIMPLE_NAMES);
                }
                if (args.equals("-disambiguateTvars")) {
                    features.remove(RichFormatterFeature.UNIQUE_TYPEVAR_NAMES);
                } else if (args.equals("disambiguateTvars")) {
                    features.add(RichFormatterFeature.UNIQUE_TYPEVAR_NAMES);
                }
            }
        }
    }

    /**
     * Returns a list of all the features supported by the rich formatter.
     *
     * @return list of supported features
     */
    public RichFormatterFeature[] getAvailableFeatures() {
        return RichFormatterFeature.values();
    }

    /**
     * Enable a specific feature on this rich formatter.
     *
     * @param feature feature to be enabled
     */
    public void enable(RichFormatterFeature feature) {
        features.add(feature);
    }

    /**
     * Disable a specific feature on this rich formatter.
     *
     * @param feature feature to be disabled
     */
    public void disable(RichFormatterFeature feature) {
        features.remove(feature);
    }

    /**
     * Is a given feature enabled on this formatter?
     *
     * @param feature feature to be tested
     */
    public boolean isEnabled(RichFormatterFeature feature) {
        return features.contains(feature);
    }

    /**
     * The advanced formatting features provided by the rich formatter
     */
    public enum RichFormatterFeature {
        /**
         * a list of additional info regarding a given type/symbol
         */
        WHERE_CLAUSES,
        /**
         * full class names simplification (where possible)
         */
        SIMPLE_NAMES,
        /**
         * type-variable names disambiguation
         */
        UNIQUE_TYPEVAR_NAMES;
    }
}