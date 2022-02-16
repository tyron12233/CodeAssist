package com.tyron.builder.util;

import org.jetbrains.annotations.NotNull;

import java.text.DecimalFormatSymbols;

/** Static methods for dealing with floating point numbers in string decimal form. */
public class DecimalUtils {
    /**
     * Removes trailing zeros after the decimal dot and also the dot itself if there are no non-zero
     * digits after it. Use {@link #trimInsignificantZeros(String, DecimalFormatSymbols)} instead of
     * this method if locale specific behavior is desired.
     *
     * @param floatingPointNumber the string representing a floating point number
     * @return the original number with trailing zeros removed
     */
    @NotNull
    public static String trimInsignificantZeros(@NotNull String floatingPointNumber) {
        return trimInsignificantZeros(floatingPointNumber, '.', "E");
    }

    /**
     * Removes trailing zeros after the decimal separator and also the decimal separator itself if
     * there are no non-zero digits after it.
     *
     * @param floatingPointNumber the string representing a floating point number
     * @param symbols the decimal format symbols
     * @return the original number with trailing zeros removed
     */
    public static String trimInsignificantZeros(
            @NotNull String floatingPointNumber, @NotNull DecimalFormatSymbols symbols) {
        return trimInsignificantZeros(
                floatingPointNumber, symbols.getDecimalSeparator(), symbols.getExponentSeparator());
    }

    /**
     * Removes trailing zeros after the decimal separator and also the decimal separator itself if
     * there are no non-zero digits after it.
     *
     * @param floatingPointNumber the string representing a floating point number
     * @param decimalSeparator the decimal separator
     * @param exponentialSeparator the string used to separate the mantissa from the exponent
     * @return the original number with trailing zeros removed
     */
    public static String trimInsignificantZeros(
            @NotNull String floatingPointNumber,
            char decimalSeparator,
            String exponentialSeparator) {
        int pos = floatingPointNumber.lastIndexOf(decimalSeparator);
        if (pos < 0) {
            return floatingPointNumber;
        }
        if (pos == 0) {
            pos = 2;
        }

        int exponent =
                CharSequences.indexOfIgnoreCase(floatingPointNumber, exponentialSeparator, pos);
        int i = exponent >= 0 ? exponent : floatingPointNumber.length();
        while (--i > pos) {
            if (floatingPointNumber.charAt(i) != '0') {
                i++;
                break;
            }
        }
        if (exponent < 0) {
            return floatingPointNumber.substring(0, i);
        } else if (exponent == i) {
            return floatingPointNumber;
        } else {
            return floatingPointNumber.substring(0, i) + floatingPointNumber.substring(exponent);
        }
    }

    /** Do not instantiate. All methods are static. */
    private DecimalUtils() {}
}
