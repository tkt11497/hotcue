package com.gcn.voice.call;

import java.util.HashMap;
import java.util.Map;

public final class CollectionsUtil {
    private CollectionsUtil() {}

    public static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new HashMap<>();
        if (values == null) return map;
        for (int i = 0; i + 1 < values.length; i += 2) {
            Object key = values[i];
            Object value = values[i + 1];
            if (key instanceof String) {
                map.put((String) key, value);
            }
        }
        return map;
    }
}
