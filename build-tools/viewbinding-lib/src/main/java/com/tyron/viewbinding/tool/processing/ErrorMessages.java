/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tyron.viewbinding.tool.processing;

public class ErrorMessages {
    private static final String BUGANIZER_URL =
            "https://issuetracker.google.com/issues/new?component=192721&template=1096850";

    public static final String INCLUDE_INSIDE_MERGE =
            "<include> elements are not supported as direct children of <merge> elements";

    public static final String FOUND_LAYOUT_BUT_NOT_ENABLED =
            "Found <layout> but data binding is not enabled.\n" +
            "\n" +
            "Add buildFeatures.dataBinding = true to your build.gradle to enable it.";

    public static final String UNDEFINED_VARIABLE =
            "Could not find identifier '%s'\n" +
            "\n" +
            "Check that the identifier is spelled correctly, and that no <import> or <variable> tags are missing.";

    public static final String CANNOT_FIND_SETTER_CALL =
            "Cannot find a setter for <%s %s> that accepts parameter type '%s'\n" +
            "\n" +
            "If a binding adapter provides the setter, check that the adapter is annotated correctly and that the parameter type matches.";

    public static final String CANNOT_RESOLVE_TYPE =
            "Cannot resolve type '%s'";

    public static final String MULTI_CONFIG_LAYOUT_CLASS_NAME_MISMATCH =
            "<data class='%s'> is not defined consistently on alternative layout '%s'";

    public static final String MULTI_CONFIG_VARIABLE_TYPE_MISMATCH =
            "<variable name='%s' type='%s'> is not defined consistently on alternative layout '%s'";

    public static final String MULTI_CONFIG_IMPORT_TYPE_MISMATCH =
            "<import alias='%s' type='%s'> is not defined consistently on alternative layout '%s'";

    public static final String MULTI_CONFIG_ID_USED_AS_IMPORT =
            "<include id='%s'> conflicts with an ID used by a view in this layout";

    public static final String ROOT_TAG_NOT_SUPPORTED =
            "You must target API level 14 or greater to support 'android:tag' on root elements of data bound layouts";

    public static final String SYNTAX_ERROR =
            "Syntax error: %s";

    public static final String CANNOT_FIND_GETTER_CALL =
            "Cannot find a getter for <%s %s> that accepts parameter type '%s'\n" +
            "\n" +
            "If a binding adapter provides the getter, check that the adapter is annotated correctly and that the parameter type matches.";

    public static final String EXPRESSION_NOT_INVERTIBLE =
            "The expression '%s' cannot be inverted, so it cannot be used in a two-way binding\n" +
            "\n" +
            "Details: %s";

    public static final String TWO_WAY_EVENT_ATTRIBUTE =
            "The attribute '%s' is generated and reserved for two-way data binding so an expression cannot be assigned to it";

    public static final String CANNOT_FIND_ABSTRACT_METHOD =
            "Cannot assign callback expression to '%s'\n" +
            "\n" +
            "Make sure you aren't using lambda syntax if the expression should only return a value directly";

    public static final String CALLBACK_ARGUMENT_COUNT_MISMATCH =
            "Number of lambda parameters is incorrect\n" +
            "\n" +
            "'%s::%s' accepts %d parameter(s), but the assigned expression uses %d parameter(s). The expression should have no " +
            "parameters or an equal number of parameters.";

    public static String callbackReturnTypeMismatchError(
        String methodName,
        String expectedReturnType,
        String expression,
        String expressionReturnType
    ) {
        return "Invalid return type in callback.\n"
               + "Callback method `" + methodName + "` is expected to return `"
               + expectedReturnType + "` but expression `"
               + expression + "` returns `" + expressionReturnType + "`";
    }

    public static final String DUPLICATE_CALLBACK_ARGUMENT =
            "Callback parameter '%s' is not unique";

    public static final String CALLBACK_VARIABLE_NAME_CLASH =
            "Callback parameter '%s' shadows variable '%s %s'";

    public static final String CANNOT_UNBOX_TYPE =
            "Cannot call 'safeUnbox' on '%s' as it is not a boxed, primitive type";

    public static final String CANNOT_FIND_METHOD_ON_OWNER =
            "Cannot find method '%s::%s'";

    public static final String ARGUMENT_COUNT_MISMATCH =
            "Unexpected parameter count\n" +
            "\n" +
            "Expected: %d\n" +
            "Found: %d";

    public static final String RECURSIVE_OBSERVABLE =
            "Observable fields (LiveData, Observable etc) cannot contain a value type of themselves: %s .\n" +
            "\n" +
            "This would create a situation where data binding would need to unwrap an observable indefinitely." +
            "(e.g. unwrapping a class like `Foo extends Observable<Foo>` would result into another `Foo`)";

    public static final String DUPLICATE_VIEW_OR_INCLUDE_ID =
            "<%s id='%s'> conflicts with another tag that has the same ID";

    public static final String UNEXPECTED_ERROR_IN_LAYOUT =
            "Unexpected error while processing layout file: %s.xml\n" +
            "\n" +
            "Please file a bug on " + BUGANIZER_URL + " with a sample project that reproduces the problem.";
}
