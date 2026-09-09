package jdk.jfr;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE, ElementType.FIELD})
public @interface Registered { boolean value() default true; }
