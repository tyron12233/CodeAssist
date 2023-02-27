package org.intellij.lang.annotations;

import org.jetbrains.annotations.NonNls;

import java.lang.annotation.*;

/**
 * <p>This annotation intended to help IntelliJ IDEA and other IDEs to detect and auto-complete int and String constants used as an enumeration.
 * For example, in the {@link java.awt.Label#Label(String, int)} constructor the <code><b>alignment</b></code> parameter can be one of the following
 * int constants: {@link java.awt.Label#LEFT}, {@link java.awt.Label#CENTER} or {@link java.awt.Label#RIGHT}
 *
 * <p>So, if <code>@MagicConstant</code> annotation applied to this constructor, the IDE will check the constructor usages for the allowed values.
 * <p>E.g.<br>
 *  <pre>{@code
 * new Label("text", 0); // 0 is not allowed
 * new Label("text", Label.LEFT); // OK
 * }</pre>
 *
 * <p>
 * <code>@MagicConstant</code> can be applied to:
 * <ul>
 *  <li> Field, local variable, parameter.
 *
 *  <br>E.g. <br>
 * <pre>{@code @MagicConstant(intValues = {TOP, CENTER, BOTTOM})
 * int textPosition;
 * }</pre>
 * The IDE will check expressions assigned to the variable for allowed values:
 * <pre>{@code
 *  textPosition = 0; // not allowed
 *  textPosition = TOP; // OK
 * }</pre>
 *
 * <li> Method
 *
 * <br>E.g.<br>
 * <pre>{@code @MagicConstant(flagsFromClass = java.lang.reflect.Modifier.class)
 *  public native int getModifiers();
 * }</pre>
 *
 * The IDE will analyse getModifiers() method calls and check if its return value is used with allowed values:<br>
 * <pre>{@code
 *  if (aClass.getModifiers() == 3) // not allowed
 *  if (aClass.getModifiers() == Modifier.PUBLIC) // OK
 * }</pre>
 *
 * <li>Annotation class<br>
 * Annotation class annotated with <code>@MagicConstant</code> created alias you can use to annotate
 * everything as if it was annotated with <code>@MagicConstant</code> itself.
 *
 * <br>E.g.<br>
 * <pre>{@code @MagicConstant(flags = {Font.PLAIN, Font.BOLD, Font.ITALIC}) }</pre>
 * <pre>{@code @interface FontStyle {} }</pre>
 *
 * The IDE will check constructs annotated with @FontStyle for allowed values:<br>
 * <code>@FontStyle int myStyle = 3; // not allowed<br></code>
 * <code>@FontStyle int myStyle = Font.BOLD | Font.ITALIC; // OK</code><br>
 *
 * </ul>
 *
 * The <code>@MagicConstant</code> annotation has SOURCE retention, i.e. it is removed upon compilation and does not create any runtime overhead.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target({
          ElementType.FIELD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE,
          ElementType.ANNOTATION_TYPE,
          ElementType.METHOD
        })
public @interface MagicConstant {
  /**
   * @return int values (typically named constants) which are allowed here.
   * E.g.
   * <pre>
   * {@code
   * void setConfirmOpenNewProject(@MagicConstant(intValues = {OPEN_PROJECT_ASK, OPEN_PROJECT_NEW_WINDOW, OPEN_PROJECT_SAME_WINDOW})
   *                               int confirmOpenNewProject);
   * }</pre>
   */
  long[] intValues() default {};

  /**
   * @return String values (typically named constants) which are allowed here.
   */
  @NonNls String[] stringValues() default {};

  /**
   * @return allowed int flags (i.e. values (typically named constants) which can be combined with bitwise OR operator (|).
   * The difference from the {@link #intValues()} is that flags are allowed to be combined (via plus:+ or bitwise OR: |) whereas values aren't.
   * The literals "0" and "-1" are also allowed to denote absence and presense of all flags respectively.
   *
   * E.g.
   * <pre>
   * {@code @MagicConstant(flags = {HierarchyEvent.PARENT_CHANGED,HierarchyEvent.DISPLAYABILITY_CHANGED,HierarchyEvent.SHOWING_CHANGED})
   * int hFlags;
   *
   * hFlags = 3; // not allowed; should be "magic" constant.
   * if (hFlags & (HierarchyEvent.PARENT_CHANGED | HierarchyEvent.SHOWING_CHANGED) != 0); // OK: combined several constants via bitwise OR
   * }</pre>
   */
  long[] flags() default {};

  /**
   * @return allowed values which are defined in the specified class static final constants.
   *
   * E.g.
   * <pre>
   * {@code @MagicConstant(valuesFromClass = Cursor.class)
   * int cursorType;
   *
   * cursorType = 11; // not allowed; should be "magic" constant.
   * cursorType = Cursor.E_RESIZE_CURSOR; // OK: "magic" constant used.
   * }</pre>
   */
  Class<?> valuesFromClass() default void.class;

  /**
   * @return allowed int flags which are defined in the specified class static final constants.
   * The difference from the {@link #valuesFromClass()} is that flags are allowed to be combined (via plus:+ or bitwise OR: |) whereas values aren't.
   * The literals "0" and "-1" are also allowed to denote absence and presense of all flags respectively.
   *
   * E.g.
   * <pre>
   * {@code @MagicConstant(flagsFromClass = java.awt.InputEvent.class)
   * int eventMask;
   *
   * eventMask = 10; // not allowed; should be "magic" constant.
   * eventMask = InputEvent.CTRL_MASK | InputEvent.ALT_MASK; // OK: combined several constants via bitwise OR
   * }</pre>
   */
  Class<?> flagsFromClass() default void.class;
}