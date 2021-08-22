package com.hedera.services.files.annotations;

import javax.inject.Qualifier;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target({ ElementType.METHOD, ElementType.PARAMETER })
@Qualifier
@Retention(RUNTIME)
public @interface PropertiesInterceptor {
}
