/*
 * Copyright 2015 the original author or authors.
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

package com.tyron.builder.tooling.events.test;

import com.tyron.builder.tooling.events.FinishEvent;
import com.tyron.builder.tooling.events.test.TestFailureResult;
import com.tyron.builder.tooling.events.test.TestOperationResult;
import com.tyron.builder.tooling.events.test.TestProgressEvent;
import com.tyron.builder.tooling.events.test.TestSkippedResult;
import com.tyron.builder.tooling.events.test.TestSuccessResult;

/**
 * An event that informs about a test having finished its execution. You can query the result of the
 * test using {@link #getResult()}.
 *
 * @since 2.4
 */
public interface TestFinishEvent extends TestProgressEvent, FinishEvent {

    /**
     * Returns the result of the finished test operation. Currently, the result will be one of the following
     * sub-types:
     *
     * <ul>
     *     <li>{@link TestSuccessResult}</li>
     *     <li>{@link TestSkippedResult}</li>
     *     <li>{@link TestFailureResult}</li>
     * </ul>
     *
     * @return the result of the finished test operation
     */
    @Override
    TestOperationResult getResult();

}
