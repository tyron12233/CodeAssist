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

import java.util.Iterator;

/**
 * SimpleArrayIterator
 *
 * @author adityasharat
 */

public class SimpleArrayIterator<E> implements Iterator<E> {

  private final E[] elements;
  private int cursor;

  public SimpleArrayIterator(E[] elements) {
    this.elements = elements;
    cursor = 0;
  }

  public static Iterator<Integer> createIntArrayIterator(final int[] elements) {
    return new Iterator<Integer>() {

      private int cursor;

      @Override
      public boolean hasNext() {
        return cursor < elements.length;
      }

      @Override
      public Integer next() {
        Integer e = elements[cursor];
        cursor++;
        return e;
      }
    };
  }

  @Override
  public boolean hasNext() {
    return cursor < elements.length;
  }

  @Override
  public E next() {
    E e = elements[cursor];
    cursor++;
    return e;
  }

  @Override
  public void remove() {
    throw new UnsupportedOperationException("remove() is not allowed.");
  }

}
