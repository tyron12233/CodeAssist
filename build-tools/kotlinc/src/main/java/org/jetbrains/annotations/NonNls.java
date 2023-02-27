package org.jetbrains.annotations;

import java.lang.annotation.*;

/**
 * Specifies that an element of the program is not a user-visible string which needs to be localized,
 * or does not contain such strings. This annotation is intended to be used by localization tools for
 * detecting strings which should not be reported as requiring localization.
 * <ul>
 * <li>If a method parameter is annotated with {@code NonNls}, the strings passed
 * as values of this parameter are not reported as requiring localization.
 * Also, if the parameter of a property setter method is annotated with {@code NonNls}, values
 * of that property in UI Designer forms are never highlighted as hard-coded strings.</li>
 * <li>If a field is annotated with {@code NonNls}, all string literals found in the
 * initializer of the field are not reported as requiring localization.</li>
 * <li>If a method is called on a field, parameter or local variable annotated with {@code NonNls},
 * string literals passed as parameters to the method are not reported as requiring localization.
 * <li>If a field, parameter or local variable annotated with {@code NonNls} is passed as a
 * parameter to the {@code equals()} method invoked on a string literal, the literal is not
 * reported as requiring localization.</li>
 * <li>If a field, parameter or local variable annotated with {@code NonNls} is found at
 * the left side of an assignment expression, all string literals in the right side
 * of the expression are not reported as requiring localization.</li>
 * <li>If a method is annotated with {@code NonNls}, string literals returned from the method
 * are not reported as requiring localization.</li>
 * <li>If a class is annotated with {@code NonNls}, all string literals in
 * the class and all its subclasses are not reported as requiring localization.</li>
 * <li>If a package is annotated with {@code NonNls}, all string literals in
 * the package and all its subpackages are not reported as requiring localization.</li>
 * </ul>
 *
 * <p>
 * This annotation also could be used as a meta-annotation, to define derived annotations for convenience.
 * E.g. the following annotation could be defined to annotate the strings that represent UUIDs,
 * thus should not be localized:
 *
 * <pre>
 * &#64;NonNls
 * &#64;interface UUID {}
 * </pre>
 * <p>
 * Note that using the derived annotation as meta-annotation is not supported.
 * Meta-annotation works only one level deep.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE, ElementType.TYPE, ElementType.PACKAGE})
public @interface NonNls {

}