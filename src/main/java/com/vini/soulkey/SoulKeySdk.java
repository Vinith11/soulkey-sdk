package com.vini.soulkey;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Core SDK class: private map of known keys -> values.
 */
public class SoulKeySdk {
    private final Map<String, Object> map;

    public SoulKeySdk() {
        Map<String, Object> m = new HashMap<>();
        // Example default values (these are the "secret" values the SDK knows)
        // Inside SoulKeySdk constructor
        m.put("08a10664-aadd-440f-9836-612dc8ac397a.THRESHOLD2", 1000);           // integer -> Integer/Long
        m.put("08a10664-aadd-440f-9836-612dc8ac397a.DECIMAL1", new BigDecimal("12.345")); // decimal -> BigDecimal
        m.put("08a10664-aadd-440f-9836-612dc8ac397a.FLAG", Boolean.TRUE);         // boolean
        m.put("08a10664-aadd-440f-9836-612dc8ac397a.DB_URL", "jdbc:postgresql://..."); // string
        // add more known keys as required
        map = Collections.unmodifiableMap(m);
    }

    // Get by key (key format is projectId + "." + env)
    public Object get(String key) {
        return map.get(key);
    }

    public Map<String, Object> getAll() {
        return map;
    }
}
