package com.sb24.api.access;

import com.sb24.api.domain.Account;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** What is being acted upon, with the attributes ABAC policies may inspect. */
public record Resource(String type, String id, UUID ownerId, Map<String, Object> attrs) {
    public static Resource none() { return new Resource("NONE", null, null, Map.of()); }

    public static Resource of(String type, Object id, UUID ownerId, Map<String, Object> attrs) {
        return new Resource(type, id == null ? null : id.toString(), ownerId, attrs);
    }

    public static Resource account(Account a, Map<String, Object> extra) {
        Map<String, Object> m = new HashMap<>(extra);
        m.put("status", a.getStatus());
        m.put("accountType", a.getType().name());
        return new Resource("ACCOUNT", a.getId().toString(), a.getOwnerId(), m);
    }

    public Object attr(String key) { return attrs.get(key); }
}
