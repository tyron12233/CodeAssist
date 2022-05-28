package com.flipkart.android.proteus.util

const val GRAVITY_CENTER_VERTICAL = 0x10
const val GRAVITY_TOP = 0x30
const val GRAVITY_BOTTOM = 0x50
const val GRAVITY_FILL_VERTICAL = 0x70
const val GRAVITY_CENTER_HORIZONTAL = 0x01
const val GRAVITY_LEFT = 0x03
const val GRAVITY_RIGHT = 0x05
const val GRAVITY_FILL_HORIZONTAL = 0x07
const val GRAVITY_CENTER = 0x11
const val GRAVITY_FILL = 0x77
const val GRAVITY_CLIP_HORIZONTAL = 0x08
const val GRAVITY_CLIP_VERTICAL = 0x80
const val GRAVITY_RTL_FLAG = 0x800000
const val GRAVITY_START = 0x800003
const val GRAVITY_END = 0x800005

// region CodeAssist added
const val GRAVITY_VALUE_CENTER = "center"
const val GRAVITY_VALUE_LEFT = "left"
const val GRAVITY_VALUE_RIGHT = "right"
const val GRAVITY_VALUE_START = "start"
const val GRAVITY_VALUE_END = "end"
const val GRAVITY_VALUE_BOTTOM = "bottom"
const val GRAVITY_VALUE_TOP = "top"
const val GRAVITY_VALUE_FILL_HORIZONTAL = "fill_horizontal"
const val GRAVITY_VALUE_FILL_VERTICAL = "fill_vertical"
const val GRAVITY_VALUE_CENTER_HORIZONTAL = "center_horizontal"
const val GRAVITY_VALUE_CENTER_VERTICAL = "center_vertical"
const val GRAVITY_VALUE_CLIP_HORIZONTAL = "clip_horizontal"
const val GRAVITY_VALUE_CLIP_VERTICAL = "clip_vertical"
const val GRAVITY_VALUE_FILL = "fill"
// endregion

/**
 * Utility for mapping an integer gravity value to a user readable string.
 *
 * CodeAssist: Adapted from com.android.tools.idea.layoutinspector.pipeline.legacy.GravityIntMapping
 */
class GravityIntMapping {
  private val intMapping = IntFlagMapping()

  fun fromIntValue(value: Int): Set<String> {
    val values = intMapping.of(value)
    if ((value and GRAVITY_RTL_FLAG) != 0) {
      if (values.remove(GRAVITY_VALUE_LEFT)) {
        values.add(GRAVITY_VALUE_START)
      }
      if (values.remove(GRAVITY_VALUE_RIGHT)) {
        values.add(GRAVITY_VALUE_END)
      }
    }
    return values
  }

  init {
    intMapping.add(GRAVITY_FILL, GRAVITY_FILL, GRAVITY_VALUE_FILL)

    intMapping.add(GRAVITY_FILL_VERTICAL, GRAVITY_FILL_VERTICAL, GRAVITY_VALUE_FILL_VERTICAL)
    intMapping.add(GRAVITY_FILL_VERTICAL, GRAVITY_TOP, GRAVITY_VALUE_TOP)
    intMapping.add(GRAVITY_FILL_VERTICAL, GRAVITY_BOTTOM, GRAVITY_VALUE_BOTTOM)

    intMapping.add(GRAVITY_FILL_HORIZONTAL, GRAVITY_FILL_HORIZONTAL, GRAVITY_VALUE_FILL_HORIZONTAL)
    intMapping.add(GRAVITY_FILL_HORIZONTAL, GRAVITY_LEFT, GRAVITY_VALUE_LEFT)
    intMapping.add(GRAVITY_FILL_HORIZONTAL, GRAVITY_RIGHT, GRAVITY_VALUE_RIGHT)

    intMapping.add(GRAVITY_FILL, GRAVITY_CENTER, GRAVITY_VALUE_CENTER)
    intMapping.add(GRAVITY_FILL_VERTICAL, GRAVITY_CENTER_VERTICAL, GRAVITY_VALUE_CENTER_VERTICAL)
    intMapping.add(GRAVITY_FILL_HORIZONTAL, GRAVITY_CENTER_HORIZONTAL, GRAVITY_VALUE_CENTER_HORIZONTAL)

    intMapping.add(GRAVITY_CLIP_VERTICAL, GRAVITY_CLIP_VERTICAL, GRAVITY_VALUE_CLIP_VERTICAL)
    intMapping.add(GRAVITY_CLIP_HORIZONTAL, GRAVITY_CLIP_HORIZONTAL, GRAVITY_VALUE_CLIP_HORIZONTAL)
  }
}
