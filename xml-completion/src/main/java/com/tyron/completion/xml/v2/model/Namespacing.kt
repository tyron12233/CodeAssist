package com.tyron.completion.xml.v2.model

enum class Namespacing {
  /**
   * Resources are not namespaced.
   *
   *
   * They are merged at the application level, as was the behavior with AAPT1
   */
  DISABLED,

  /**
   * Resources must be namespaced.
   *
   *
   * Each library is compiled in to an AAPT2 static library with its own namespace.
   *
   *
   * Projects using this *cannot* consume non-namespaced dependencies.
   */
  REQUIRED
}