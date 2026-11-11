package com.sb24.api.access;

import java.lang.annotation.*;

/**
 * Every /api/v1 handler must declare the permission it needs (or be @PublicEndpoint). A handler with neither is
 * refused at run time (fail closed), so a forgotten annotation can never become an open door.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresPermission {
    String value();
}
