package com.sb24.api;

import static org.junit.jupiter.api.Assertions.*;

import com.sb24.api.access.Perm;
import com.sb24.api.access.PublicEndpoint;
import com.sb24.api.access.Rbac;
import com.sb24.api.access.RequiresPermission;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Build-time guard: an endpoint added without a permission fails CI instead of silently shipping. */
class PermissionCoverageIT extends BaseIT {
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping;

    @Test
    void everyApiEndpointDeclaresAPermissionOrIsExplicitlyPublic() {
        List<String> unprotected = new ArrayList<>();
        int checked = 0;
        for (var e : mapping.getHandlerMethods().entrySet()) {
            HandlerMethod hm = e.getValue();
            if (!hm.getBeanType().getPackageName().startsWith("com.sb24.api.web")) continue;
            checked++;
            boolean ok = AnnotatedElementUtils.hasAnnotation(hm.getMethod(), PublicEndpoint.class) || AnnotatedElementUtils.hasAnnotation(hm.getBeanType(), PublicEndpoint.class)
                    || AnnotatedElementUtils.findMergedAnnotation(hm.getMethod(), RequiresPermission.class) != null
                    || AnnotatedElementUtils.findMergedAnnotation(hm.getBeanType(), RequiresPermission.class) != null;
            if (!ok) unprotected.add(e.getKey().toString());
        }
        assertTrue(checked > 40, "expected to inspect the whole API, saw " + checked);
        assertTrue(unprotected.isEmpty(), "endpoints without @RequiresPermission/@PublicEndpoint: " + unprotected);
    }

    @Test
    void everyDeclaredPermissionExistsInTheCatalogueAndIsGrantedToSomeRole() throws Exception {
        Set<String> declared = new HashSet<>();
        for (Field f : Perm.class.getFields()) declared.add((String) f.get(null));
        for (var e : mapping.getHandlerMethods().entrySet()) {
            RequiresPermission rp = AnnotatedElementUtils.findMergedAnnotation(e.getValue().getMethod(), RequiresPermission.class);
            if (rp != null && e.getValue().getBeanType().getPackageName().startsWith("com.sb24.api.web")) assertTrue(declared.contains(rp.value()), "unknown permission " + rp.value());
        }
        assertEquals(declared, Rbac.PERMISSIONS.keySet(), "Perm constants and the seeded catalogue must match");
        Set<String> granted = new HashSet<>();
        Rbac.ROLES.values().forEach(r -> granted.addAll(r.permissions()));
        assertEquals(declared, granted, "a permission that no role grants is dead code");
    }
}
