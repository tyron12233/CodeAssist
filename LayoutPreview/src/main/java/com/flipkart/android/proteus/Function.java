/*
 * Copyright 2019 Flipkart Internet Pvt. Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.flipkart.android.proteus;

import android.annotation.SuppressLint;
import android.content.Context;

import com.flipkart.android.proteus.parser.ParseHelper;
import com.flipkart.android.proteus.toolbox.Utils;
import com.flipkart.android.proteus.value.Array;
import com.flipkart.android.proteus.value.Primitive;
import com.flipkart.android.proteus.value.Value;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

import androidx.annotation.NonNull;

/**
 * @author Aditya Sharat on 18-05-2015.
 */
public abstract class Function {

  // SPECIAL

  public static final Function NOOP = new Function() {
    @NonNull
    @Override
    public Value call(Context context, Value data, int dataIndex, Value... arguments) throws Exception {
      return ProteusConstants.EMPTY_STRING;
    }

    @Override
    public String getName() {
      return "noop";
    }
  };

  @SuppressLint("SimpleDateFormat")
  public static final Function DATE = new Function() {

    private SimpleDateFormat from = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private SimpleDateFormat to = new SimpleDateFormat("E, d MMM");

    @NonNull
    @Override
    public Value call(Context context, Value data, int dataIndex, Value... arguments) throws Exception {
      Date in = getFromFormat(arguments).parse(arguments[0].getAsString());
      String out = getToFormat(arguments).format(in);
      return new Primitive(out);
    }

    private SimpleDateFormat getFromFormat(Value[] arguments) {
      if (arguments.length > 2) {
        return new SimpleDateFormat(arguments[2].getAsString());
      } else {
        return from;
      }
    }

    private SimpleDateFormat getToFormat(Value[] arguments) {
      if (arguments.length > 1) {
        return new SimpleDateFormat(arguments[1].getAsString());
      } else {
        return to;
      }
    }

    @Override
    public String getName() {
      return "date";
    }
  };

  public static final Function FORMAT = new Function() {
    @NonNull
    @Override
    public Value call(Context context, Value data, int dataIndex, Value... arguments) throws Exception {
      String template = arguments[0].getAsString();
      String[] values = new String[arguments.length - 1];
      for (int i = 1; i < arguments.length; i++) {
        values[i - 1] = arguments[i].getAsString();
      }
      return new Primitive(String.format(template, (Object[]) values));
    }

    @Override
    public String getName() {
      return "format";
    }
  };

  public static final Function JOIN = new Function() {

    private static final String DEFAULT_DELIMITER = ", ";

    @NonNull
    @Override
    public Value call(Context context, Value data, int dataIndex, Value... arguments) throws Exception {
      return new Primitive(Utils.join(arguments[0].getAsArray(), getDelimiter(arguments)));
    }

    private String getDelimiter(Value[] arguments) {
      if (arguments.length > 1) {
        return arguments[1].getAsString();
      }
      return DEFAULT_DELIMITER;
    }

    @Override
    public String getName() {
      return "join";
    }
  };

  public static final Function NUMBER = new Function() {

    private final DecimalFormat DEFAULT_FORMATTER = new DecimalFormat("#,###");

    @NonNull
    @Override
    public Value call(Context context, Value data, int dataIndex, Value... arguments) throws Exception {
      double number = Double.parseDouble(arguments[0].getAsString());
      DecimalFormat formatter = getFormatter(arguments);
      formatter.setRoundingMode(RoundingMode.FLOOR);
      formatter.setMinimumFractionDigits(0);
      formatter.setMaximumFractionDigits(2);
      return new Primitive(formatter.format(number));
    }

    private DecimalFormat getFormatter(Value[] arguments) {
      if (arguments.length > 1) {
        return new DecimalFormat(arguments[1].getAsString());
      }
      return DEFAULT_FORMATTER;
    }

    @Override
    public String getName() {
      return "number";
    }
  };

  // Mathematical

  public static final Function ADD = new Function() {
    @NonNull
    @Override
    public Value call(Context context, Value data, int dataIndex, Value... arguments) throws Exception {
      double sum = 0;

      for (Value argument : arguments) {
        sum = sum + argument.getAsDouble();
      }

      return new Primitive(sum);
    }

    @Override
    public String getName() {
      return "add";
    }
  };

  public static final Function SUBTRACT = new Function() {
    @NonNull
    @Override
    public Value call(Context context, Value data, int dataIndex, Value... arguments) throws Exception {
      double sum = arguments[0].getAsDouble();

      for (int i = 1; i < arguments.length; i++) {
        sum = sum - arguments[i].getAsDouble();
      }

      return new Primitive(sum);
    }

    @Override
    public String getName() {
      return "sub";
    }
  };

  public static final Function MULTIPLY = new Function() {
    @NonNull
    @Override
    public Value call(Context context, Value data, int dataIndex, Value... arguments) throws Exception {
      double product = 1;

      for (Value argument : arguments) {
        product = product * argument.getAsDouble();
      }

      return new Primitive(product);
    }

    @Override
    public String getName() {
      return "mul";
    }
  };

  public static final Function DIVIDE = new Function() {
    @NonNull
    @Override
    public Value call(Context context, Value data, int dataIndex, Value... arguments) throws Exception {
      double quotient = arguments[0].getAsDouble();

      for (int i = 1; i < arguments.length; i++) {
        quotient = quotient / arguments[i].getAsDouble();
      }

      return new Primitive(quotient);
    }

    @Override
    public String getName() {
      return "div";
    }
  };

  public static final Function MODULO = new Function() {
    @NonNull
    public Value call(Context context, Value data, int dataIndex, Value... arguments) throws Exception {
      double remainder = arguments[0].getAsDouble();

      for (int i = 1; i < arguments.length; i++) {
        remainder = remainder % arguments[i].getAsDouble();
      }

      return new Primitive(remainder);
    }

    @Override
    public String getName() {
      return "mod";
    }
  };

  // Logical

  public static final Function AND = new Function() {
    @NonNull
    @Override
    public Value call(Context context, Value data, int dataIndex, Value... arguments) throws Exception {
      if (arguments.length < 1) {
        return ProteusConstants.FALSE;
      }
      boolean bool = true;
      for (Value argument : arguments) {
        bool = ParseHelper.parseBoolean(argument);
        if (!bool) {
          break;
        }
      }

      return bool ? ProteusConstants.TRUE : ProteusConstants.FALSE;
    }

    @Override
    public String getName() {
      return "and";
    }
  };

  public static final Function OR = new Function() {
    @NonNull
    @Override
    public Value call(Context context, Value data, int dataIndex, Value... arguments) throws Exception {
      if (arguments.length < 1) {
        return ProteusConstants.FALSE;
      }
      boolean bool = false;
      for (Value argument : arguments) {
        bool = ParseHelper.parseBoolean(argument);
        if (bool) {
          break;
        }
      }

      return bool ? ProteusConstants.TRUE : ProteusConstants.FALSE;
    }

    @Override
    public String getName() {
      return "or";
    }
  };

  // Unary

  public static final Function NOT = new Function() {
    @NonNull
    @Override
    public Value call(Context context, Value data, int dataIndex, Value... arguments) throws Exception {
      if (arguments.length < 1) {
        return ProteusConstants.TRUE;
      }
      return ParseHelper.parseBoolean(arguments[0]) ? ProteusConstants.FALSE : ProteusConstants.TRUE;
    }

    @Override
    public String getName() {
      return "not";
    }
  };

  // Comparison

  public static final Function EQUALS = new Function() {
    @NonNull
    @Override
    public Value call(Context context, Value data, int dataIndex, Value... arguments) throws Exception {
      if (arguments.length < 2) {
        return ProteusConstants.FALSE;
      }

      Value x = arguments[0];
      Value y = arguments[1];
      boolean bool = false;
      if (x.isPrimitive() && y.isPrimitive()) {
        bool = x.getAsPrimitive().equals(y.getAsPrimitive());
      }

      return bool ? ProteusConstants.TRUE : ProteusConstants.FALSE;
    }

    @Override
    public String getName() {
      return "eq";
    }
  };

  public static final Function LESS_THAN = new Function() {
    @NonNull
    @Override
    public Value call(Context context, Value data, int dataIndex, Value... arguments) throws Exception {
      if (arguments.length < 2) {
        return ProteusConstants.FALSE;
      }

      Value x = arguments[0];
      Value y = arguments[1];
      boolean bool = false;
      if (x.isPrimitive() && y.isPrimitive()) {
        bool = x.getAsPrimitive().getAsDouble() < y.getAsPrimitive().getAsDouble();
      }

      return bool ? ProteusConstants.TRUE : ProteusConstants.FALSE;
    }

    @Override
    public String getName() {
      return "lt";
    }
  };

  public static final Function GREATER_THAN = new Function() {
    @NonNull
    @Override
    public Value call(Context context, Value data, int dataIndex, Value... arguments) throws Exception {
      if (arguments.length < 2) {
        return ProteusConstants.FALSE;
      }

      Value x = arguments[0];
      Value y = arguments[1];
      boolean bool = false;
      if (x.isPrimitive() && y.isPrimitive()) {
        bool = x.getAsPrimitive().getAsDouble() > y.getAsPrimitive().getAsDouble();
      }

      return bool ? ProteusConstants.TRUE : ProteusConstants.FALSE;
    }

    @Override
    public String getName() {
      return "gt";
    }
  };

  public static final Function LESS_THAN_OR_EQUALS = new Function() {
    @NonNull
    @Override
    public Value call(Context context, Value data, int dataIndex, Value... arguments) throws Exception {
      if (arguments.length < 2) {
        return ProteusConstants.FALSE;
      }

      Value x = arguments[0];
      Value y = arguments[1];
      boolean bool = false;
      if (x.isPrimitive() && y.isPrimitive()) {
        bool = x.getAsPrimitive().getAsDouble() <= y.getAsPrimitive().getAsDouble();
      }

      return bool ? ProteusConstants.TRUE : ProteusConstants.FALSE;
    }

    @Override
    public String getName() {
      return "lte";
    }
  };

  public static final Function GREATER_THAN_OR_EQUALS = new Function() {
    @NonNull
    @Override
    public Value call(Context context, Value data, int dataIndex, Value... arguments) throws Exception {
      if (arguments.length < 2) {
        return ProteusConstants.FALSE;
      }

      Value x = arguments[0];
      Value y = arguments[1];
      boolean bool = false;
      if (x.isPrimitive() && y.isPrimitive()) {
        bool = x.getAsPrimitive().getAsDouble() >= y.getAsPrimitive().getAsDouble();
      }

      return bool ? ProteusConstants.TRUE : ProteusConstants.FALSE;
    }

    @Override
    public String getName() {
      return "gte";
    }
  };

  // Conditional

  public static final Function TERNARY = new Function() {
    @NonNull
    @Override
    public Value call(Context context, Value data, int dataIndex, Value... arguments) throws Exception {
      Value i = arguments[0];
      Value t = arguments[1];
      Value e = arguments[2];

      return ParseHelper.parseBoolean(i) ? t : e;
    }

    @Override
    public String getName() {
      return "ternary";
    }
  };

  // String

  //String.charAt()
  public static final Function CHAR_AT = new Function() {
    @NonNull
    @Override
    public Value call(Context context, Value data, int dataIndex, Value... arguments) throws Exception {
      String string = arguments[0].getAsString();
      int index = arguments[1].getAsInt();
      char charAtIndex = string.charAt(index);
      return new Primitive(charAtIndex);
    }

    @Override
    public String getName() {
      return "charAt";
    }
  };

  //String.contains()
  public static final Function CONTAINS = new Function() {
    @NonNull
    @Override
    public Value call(Context context, Value data, int dataIndex, Value... arguments) throws Exception {
      String string = arguments[0].getAsString();
      String substring = arguments[1].getAsString();
      boolean bool = string.contains(substring);
      return new Primitive(bool);
    }

    @Override
    public String getName() {
      return "contains";
    }
  };

  //String.endsWith()
  //String.indexOf()

  //String.isEmpty()
  public static final Function IS_EMPTY = new Function() {
    @NonNull
    @Override
    public Value call(Context context, Value data, int dataIndex, Value... arguments) throws Exception {
      String string = arguments[0].getAsString();
      return new Primitive(ProteusConstants.EMPTY.equals(string));
    }

    @Override
    public String getName() {
      return "isEmpty";
    }
  };

  //String.lastIndexOf()

  //String.length()
  public static final Function LENGTH = new Function() {
    @NonNull
    @Override
    public Value call(Context context, Value data, int dataIndex, Value... arguments) throws Exception {
      Value value = arguments[0];
      int length = 0;
      if (value.isPrimitive()) {
        length = value.getAsString().length();
      } else if (value.isArray()) {
        length = value.getAsArray().size();
      }
      return new Primitive(length);
    }

    @Override
    public String getName() {
      return "length";
    }
  };

  //String.matches()
  //String.replace()
  //String.replaceAll()
  //String.replaceFirst()
  //String.split()
  //String.startsWith()
  //String.substring()
  //String.toLowerCase()
  //String.toUpperCase()

  //String.trim()
  public static final Function TRIM = new Function() {
    @NonNull
    @Override
    public Value call(Context context, Value data, int dataIndex, Value... arguments) throws Exception {
      String string = arguments[0].getAsString().trim();
      return new Primitive(string);
    }

    @Override
    public String getName() {
      return "trim";
    }
  };

  //String.subSequence()

  // Math

  //Math.abs
  //Math.ceil
  //Math.floor
  //Math.pow
  //Math.round
  //Math.random

  //Math.max
  public static final Function MAX = new Function() {
    @NonNull
    @Override
    public Value call(Context context, Value data, int dataIndex, Value... arguments) throws Exception {
      double max = arguments[0].getAsDouble();
      double current;
      for (int i = 1; i < arguments.length; i++) {
        current = arguments[i].getAsDouble();
        if (current > max) {
          max = current;
        }
      }
      return new Primitive(max);
    }

    @Override
    public String getName() {
      return "max";
    }
  };

  //Math.min
  public static final Function MIN = new Function() {
    @NonNull
    @Override
    public Value call(Context context, Value data, int dataIndex, Value... arguments) throws Exception {
      double min = arguments[0].getAsDouble();
      double current;
      for (int i = 1; i < arguments.length; i++) {
        current = arguments[i].getAsDouble();
        if (current < min) {
          min = current;
        }
      }
      return new Primitive(min);
    }

    @Override
    public String getName() {
      return "min";
    }
  };

  // Array

  public static final Function SLICE = new Function() {
    @NonNull
    @Override
    public Value call(Context context, Value data, int dataIndex, Value... arguments) throws Exception {
      Array in = arguments[0].getAsArray();
      int start = getStart(in, arguments);
      int end = getEnd(in, arguments);
      Array out = new Array();
      for (int i = start; i < end; i++) {
        out.add(in.get(i));
      }
      return out;
    }

    private int getStart(Array in, Value[] arguments) {
      if (arguments.length > 1) {
        int index = arguments[1].getAsInt();
        if (index < 0) {
          index = in.size() - index;
          if (index < 0) {
            return 0;
          }
        } else if (index > in.size()) {
          index = in.size();
        }
        return index;
      }
      return 0;
    }

    private int getEnd(Array in, Value[] arguments) {
      if (arguments.length > 2) {
        int index = arguments[2].getAsInt();
        if (index < 0) {
          index = in.size() - index;
          if (index < 0) {
            return 0;
          }
        } else if (index > in.size()) {
          index = in.size();
        }
        return index;
      }
      return in.size();
    }

    @Override
    public String getName() {
      return "slice";
    }
  };

  @NonNull
  public abstract Value call(Context context, Value data, int dataIndex, Value... arguments) throws Exception;

  public abstract String getName();
}
