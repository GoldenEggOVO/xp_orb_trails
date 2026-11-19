package dev.goldeneggovo.xporbtrails;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;

/** Immutable lookups shared by the renderer and the role-specific selectors. */
final class RoleAssets<T> {
    final Map<String, T> all;
    private final EnumMap<AssetRole, Map<String, T>> roles = new EnumMap<>(AssetRole.class);

    RoleAssets(Map<String, T> loaded) {
        all = Collections.unmodifiableMap(loaded);
        for (var role : AssetRole.values()) {
            Map<String, T> entries = new TreeMap<>();
            loaded.forEach((id, value) -> {
                if (role.accepts(id)) entries.put(id, value);
            });
            roles.put(role, Collections.unmodifiableMap(entries));
        }
    }

    Map<String, T> forRole(AssetRole role) {
        return roles.get(role);
    }
}
