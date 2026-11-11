package com.sb24.api.access;

import java.lang.annotation.*;

/** Explicit opt-out for endpoints that are meant to be reachable without a token (login, token issue). */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PublicEndpoint {}
