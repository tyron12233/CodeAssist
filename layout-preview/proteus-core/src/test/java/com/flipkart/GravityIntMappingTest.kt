package com.flipkart

import com.flipkart.android.proteus.util.*
import com.flipkart.android.proteus.util.GravityIntMapping
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * CodeAssist: Adopted from com.android.tools.idea.layoutinspector.pipeline.legacy.GravityIntMappingTest
 */
class GravityIntMappingTest {
  private val mapping by lazy(LazyThreadSafetyMode.NONE) { GravityIntMapping() }

  @Test
  fun testLeftOverridesCenter() {
    assertThat(mapping.fromIntValue(GRAVITY_CENTER or GRAVITY_LEFT))
      .containsExactly(GRAVITY_VALUE_CENTER_VERTICAL, GRAVITY_VALUE_LEFT)
  }

  @Test
  fun testTopBottomBecomesVerticalFill() {
    assertThat(mapping.fromIntValue(GRAVITY_TOP or GRAVITY_BOTTOM or GRAVITY_START))
      .containsExactly(GRAVITY_VALUE_FILL_VERTICAL, GRAVITY_VALUE_START)
  }

  @Test
  fun testLeftRightBecomesHorizontalFill() {
    assertThat(mapping.fromIntValue(GRAVITY_TOP or GRAVITY_LEFT or GRAVITY_RIGHT))
      .containsExactly(GRAVITY_VALUE_TOP, GRAVITY_VALUE_FILL_HORIZONTAL)
  }

  @Test
  fun testStartEndBecomesHorizontalFill() {
    assertThat(mapping.fromIntValue(GRAVITY_TOP or GRAVITY_START or GRAVITY_END))
      .containsExactly(GRAVITY_VALUE_TOP, GRAVITY_VALUE_FILL_HORIZONTAL)
  }

  @Test
  fun testTopBottomLeftRightSimplyBecomesFill() {
    assertThat(mapping.fromIntValue(GRAVITY_TOP or GRAVITY_BOTTOM or GRAVITY_LEFT or GRAVITY_RIGHT))
      .containsExactly(GRAVITY_VALUE_FILL)
  }

  @Test
  fun testVerticalAndHorizontalCenterSimplyBecomesCenter() {
    assertThat(mapping.fromIntValue(GRAVITY_CENTER_VERTICAL or GRAVITY_CENTER_HORIZONTAL))
      .containsExactly(GRAVITY_VALUE_CENTER)
  }
}
