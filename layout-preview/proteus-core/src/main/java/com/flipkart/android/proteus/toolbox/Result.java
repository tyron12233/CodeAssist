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

package com.flipkart.android.proteus.toolbox;

import com.flipkart.android.proteus.value.Null;
import com.flipkart.android.proteus.value.Value;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;

/**
 * Result class hold the return status and the data
 *
 * @author aditya.sharat
 */
public class Result {

  /**
   * Indicates that a valid {@link Value} was found at the specified data path.
   */
  public static final int RESULT_SUCCESS = 0;

  /**
   * Indicates that the object does not have the specified data path.
   */
  public static final int RESULT_NO_SUCH_DATA_PATH_EXCEPTION = -1;

  /**
   * Indicates that the data path specified is invalid. As an example, looking for a
   * property inside a {@link com.flipkart.android.proteus.value.Primitive} or {@link com.flipkart.android.proteus.value.Array}.
   */
  public static final int RESULT_INVALID_DATA_PATH_EXCEPTION = -2;

  /**
   * Indicates that the data path prematurely led to a {@link com.flipkart.android.proteus.value.Null}
   */
  public static final int RESULT_NULL_EXCEPTION = -3;

  /**
   * singleton for No Such Data Path Exception.
   */
  public static final Result NO_SUCH_DATA_PATH_EXCEPTION = new Result(Result.RESULT_NO_SUCH_DATA_PATH_EXCEPTION, Null.INSTANCE);

  /**
   * singleton for Invalid Data Path Exception.
   */
  public static final Result INVALID_DATA_PATH_EXCEPTION = new Result(Result.RESULT_INVALID_DATA_PATH_EXCEPTION, Null.INSTANCE);

  /**
   * singleton for Null Exception.
   */
  public static final Result NULL_EXCEPTION = new Result(Result.RESULT_NULL_EXCEPTION, Null.INSTANCE);

  /**
   * Indicates the return status of the method for a given data path. The return value
   * will be {@code RESULT_SUCCESS} if and only if the data path exists and contains
   * a valid {@link com.flipkart.android.proteus.value.Value}.
   */

  @ResultCode
  public final int RESULT_CODE;
  /**
   * The value at the specified data path.
   * {@code value} will be null if {@code RESULT_CODE} != {@code RESULT_SUCCESS}
   */

  @NonNull
  public final Value value;

  public Result(@ResultCode int RESULT_CODE, @NonNull Value value) {
    this.RESULT_CODE = RESULT_CODE;
    this.value = value;
  }

  /**
   * This method return a {@link Result} object with {@code RESULT_CODE} == {@code RESULT_SUCCESS}
   * and {@code Result#value} == {@code value}.
   *
   * @param value The {@link Value} to be wrapped.
   * @return A {@link Result} object with with {@code RESULT_CODE} == {@code RESULT_SUCCESS}.
   */
  public static Result success(Value value) {
    return new Result(RESULT_SUCCESS, value);
  }

  /**
   * @return true if and only if {@code RESULT_CODE} == {@code RESULT_SUCCESS}.
   */
  public boolean isSuccess() {
    return this.RESULT_CODE == RESULT_SUCCESS;
  }

  @IntDef({RESULT_INVALID_DATA_PATH_EXCEPTION, RESULT_NO_SUCH_DATA_PATH_EXCEPTION, RESULT_SUCCESS, RESULT_NULL_EXCEPTION})
  @Retention(RetentionPolicy.SOURCE)
  public @interface ResultCode {
  }

}