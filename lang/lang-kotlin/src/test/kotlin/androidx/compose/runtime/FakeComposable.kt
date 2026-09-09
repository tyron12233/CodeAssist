package androidx.compose.runtime

/**
 * A stand-in for the real `androidx.compose.runtime.Composable` annotation, defined in the test source set
 * so the test classpath contains a class compiled WITH `@Composable` on a method — without pulling the whole
 * Compose toolchain. `BINARY` retention matches the real annotation (an "invisible" annotation in bytecode,
 * which the ASM `@Composable` detection in `KotlinMetadata` reads). The FQN must match exactly.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE, AnnotationTarget.PROPERTY_GETTER)
annotation class Composable
