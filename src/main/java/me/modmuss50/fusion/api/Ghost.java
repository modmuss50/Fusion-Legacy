package me.modmuss50.fusion.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

@Target({ElementType.FIELD, ElementType.METHOD})
public @interface Ghost {

    boolean stripFinal() default false;

}
