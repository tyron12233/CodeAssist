package com.tyron.builder.gradle.internal.variant

import com.google.common.collect.ImmutableList
import com.tyron.builder.errors.IssueReporter
import com.tyron.builder.gradle.internal.dsl.ProductFlavor

/**
 * Computes the combination of dimensions from a [VariantInputModel] to create components/variants
 *
 * This returns a list of [DimensionCombination]
 */
class DimensionCombinator(
    private val variantInputModel : VariantInputModel<*,*,*,*>,
    private val errorReporter: IssueReporter,
    private val flavorDimensionList: List<String>
) {
    companion object {
        // fake dimension in case a flavor does not have one
        const val FAKE_DIMENSION = "agp-missing-dimension-for-sync-only"
    }

    /**
     * Computes and returns the list of variants as [ComponentIdentity] objects.
     */
    fun computeVariants() : List<DimensionCombination> {
        // different paths for flavors or no flavors to optimize things a bit

        if (variantInputModel.productFlavors.isEmpty()) {
            return computeFlavorlessVariants()
        }

        return computeVariantsWithFlavors()
    }

    /**
     * Computes [ComponentIdentity] for the case where there are no flavors.
     */
    private fun computeFlavorlessVariants() : List<DimensionCombination> {
        return if (variantInputModel.buildTypes.isEmpty()) {
            ImmutableList.of(DimensionCombinationImpl())
        } else {
            val builder = ImmutableList.builder<DimensionCombination>()

            for (buildType in variantInputModel.buildTypes.keys) {
                builder.add(
                    DimensionCombinationImpl(buildType = buildType)
                )
            }

            builder.build()
        }
    }

    /**
     * Computes [ComponentIdentity] for the case with flavors.
     */
    private fun computeVariantsWithFlavors(): List<DimensionCombination> {
        val flavorDimensionList = validateFlavorDimensions()

        // get a Map of (dimension, list of names) for the flavors
        val flavorMap = variantInputModel.productFlavors.values
            .asSequence()
            .map { it.productFlavor }
            .groupBy({ it.dimension!! }, { it.name })

        // get the flavor combos and combine them with build types.
        val builder = ImmutableList.builder<FlavorCombinationBuilder>()
        createProductFlavorCombinations(
            flavorDimensionList,
            flavorMap,
            builder,
            errorReporter
        )

        return combineFlavorsAndBuildTypes(builder.build())
    }

    /**
     * Computes [ComponentIdentity] from a list of [FlavorCombinationBuilder] by combining them
     * with build types.
     */
    private fun combineFlavorsAndBuildTypes(
        flavorCombos: List<FlavorCombinationBuilder>
    ) : List<DimensionCombination> {
        if (variantInputModel.buildTypes.isEmpty()) {
            // just convert the Accumulators to VariantConfiguration with no build type info
            return flavorCombos.map {
                DimensionCombinationImpl(productFlavors = it.flavorPairs)
            }
        } else {
            val builder = ImmutableList.builder<DimensionCombination>()

            for (buildType in variantInputModel.buildTypes.keys) {
                builder.addAll(flavorCombos.map {
                    DimensionCombinationImpl(
                        buildType = buildType,
                        productFlavors = it.flavorPairs
                    )
                })
            }

            return builder.build()
        }
    }

    /**
     * Validates the flavor dimensions.
     *
     * This checks that there's at least a dimension declared, and for the case of a single
     * dimension, assign that dimension to all flavors. This is to facilitate migrating from
     * AGP versions that did not required explicitly declared dimension name (for single dimension
     * case).
     */
    private fun validateFlavorDimensions(): List<String> {
        // ensure that there is always a dimension if there are flavors
        if (flavorDimensionList.isEmpty()) {
            errorReporter
                .reportError(
                    IssueReporter.Type.UNNAMED_FLAVOR_DIMENSION,
                    "All flavors must now belong to a named flavor dimension."
                            + " Learn more at "
                            + "https://d.android.com/r/tools/flavorDimensions-missing-error-message.html"
                )

            // because the dimension list is missing but we do have flavors, we need to rebuild the
            // dimension list. This list is not going to be correct because we cannot infer its
            // order from just gathering the dimension values set in the flavors (because the
            // flavors themselves are not ordered by dimension.
            // So this list is just so that we can keep going with sync, but this means the
            // variants returned maybe be incorrect (due to wrong order of dimensions, if there
            // is more than one dimension).
            // Studio should act on UNNAMED_FLAVOR_DIMENSION error and prevent actually doing
            // anything until the error clears anyway, but we may want to investigate not
            // returning any variant at all in this case (in the model, we still need them to
            // resolve consumers.)
            // Use a set to de-duplicate values quickly, since the order does not matter.
            val dimensions = mutableSetOf<String>()

            for (flavor in variantInputModel.productFlavors.values) {
                val productFlavor = flavor.productFlavor
                val dim = productFlavor.dimension
                if (dim == null) {
                    (productFlavor as ProductFlavor).internalDimensionDefault = FAKE_DIMENSION
                } else {
                    dimensions.add(dim)
                }
            }

            if (dimensions.isEmpty()) {
                dimensions.add(FAKE_DIMENSION)
            }

            // convert to list, see comment above regarding the order.
            return dimensions.toList()

        } else if (flavorDimensionList.size == 1) {
            // if there's only one dimension, auto-assign the dimension to all the flavors.
            val dimensionName = flavorDimensionList[0]
            for (flavorData in variantInputModel.productFlavors.values) {
                val flavor = flavorData.productFlavor
                if (flavor.dimension == null) {
                    (flavor as ProductFlavor).internalDimensionDefault = dimensionName
                }
            }
        }

        return flavorDimensionList
    }
}

/**
 * A combination of one (optional) build type and one flavor from each dimension.
 */
interface DimensionCombination {
    /**
     * Build Type name, might be replaced with access to locked DSL object once ready
     */
    val buildType: String?
    /**
     * List of flavor names, might be replaced with access to locked DSL objects once ready
     *
     * The order is properly sorted based on the associated dimension order
     */
    val productFlavors: List<Pair<String, String>>
}

/**
 * A combination of one (optional) build type and one flavor from each dimension.
 */
data class DimensionCombinationImpl(
    override val buildType: String? = null,
    override val productFlavors: List<Pair<String, String>> = listOf()
): DimensionCombination


/**
 * Recursively creates all combinations of product flavors.
 *
 * This runs through the flavor dimensions list and for the current dimension, gathers the list
 * of flavors. It then loops on these and for each, add the current (dimension, value) pair to a new
 * list and then recursively calls into the next dimension for it to loop on its own flavors and
 * do the same (fill the list.) This goes on until there's no dimension left.
 *
 * The recursion is handled by 2 objects:
 * - `dimensionIndex`: this is the dimensionIndex of the current dimension in the dimension list.
 *   Each recursive call increases this to go to the next dimension.
 *
 * - `currentFlavorCombo`: this accumulates the list of (dimension, value) pairs for the dimensions
 *   already processed. Each new dimension adds its new pairs to it (or rather to a new copy of it
 *   for each pair)
 *
 * At the end of the recursion (based on `dimensionIndex` reaching the end of the list),
 * `currentFlavorCombo` contains a pair for each dimension. This is then added to `comboList`
 *
 * This function is out of the main class because its `flavorDimensionList` param shadows
 * [DimensionCombinator.flavorDimensionList], therefore it's clearer and more obvious that
 * this is not the same value.
 *
 * @param flavorDimensionList the list of flavor dimension
 * @param flavorMap the map of (dimension, list of flavors)
 * @param comboList the list that receives the final (filled-up) [FlavorCombinationBuilder] objects.
 * @param errorReporter a [SyncIssueReporter] to report errors.
 * @param currentFlavorCombo the current accumulator containing pairs of (dimension, value) for already visited dimensions
 * @param dimensionIndex the index of the dimension this calls must handle
 */
private fun createProductFlavorCombinations(
    flavorDimensionList: List<String>,
    flavorMap: Map<String, List<String>>,
    comboList: ImmutableList.Builder<FlavorCombinationBuilder>,
    errorReporter: IssueReporter,
    currentFlavorCombo: FlavorCombinationBuilder = FlavorCombinationBuilder(),
    dimensionIndex: Int = 0
)  {
    if (dimensionIndex == flavorDimensionList.size) {
        // we visited all the dimensions, currentFlavorCombo is filled.
        comboList.add(currentFlavorCombo)
        return
    }

    // get the dimension name that matches the index we are filling.
    val dimension = flavorDimensionList[dimensionIndex]

    // from our map, get all the possible flavors in that dimension.
    val flavorList = flavorMap[dimension]

    // loop on all the flavors to add them to the current index and recursively fill the next
    // indices.
    return if (flavorList == null || flavorList.isEmpty()) {
        errorReporter.reportError(
            IssueReporter.Type.GENERIC,
            "No flavor is associated with flavor dimension '$dimension'."
        )
    } else {
        for (flavor in flavorList) {
            val newCombo = currentFlavorCombo.add(dimension, flavor)

            createProductFlavorCombinations(
                flavorDimensionList,
                flavorMap,
                comboList,
                errorReporter,
                newCombo,
                dimensionIndex + 1
            )
        }
    }
}

/**
 * Represents a combination of flavors each from a different dimension.
 *
 * This class is immutable. To add a new (dimension, value) pair to the list, use [add] that will
 * return a new instance.
 */
private class FlavorCombinationBuilder(
    /**
     * The list of (dimension, value) pairs. The order is important and match the
     * dimension priority order.
     */
    val flavorPairs: ImmutableList<Pair<String, String>> = ImmutableList.of()) {

    /**
     * Returns a new instance with the new pair information added to the existing list.
     */
    fun add(dimension: String, name: String) : FlavorCombinationBuilder {
        val builder = ImmutableList.builder<Pair<String,String>>()
        builder.addAll(flavorPairs)
        builder.add(Pair(dimension, name))

        return FlavorCombinationBuilder(builder.build())
    }

    override fun toString(): String {
        return "FlavorCombination(flavorPairs=$flavorPairs)"
    }
}
