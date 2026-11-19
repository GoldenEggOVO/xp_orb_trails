package dev.goldeneggovo.xporbtrails;

enum AssetRole {
    ORB("orb"), PICKUP("pickup"), TRAIL("trail");

    final String folder;
    private final String prefix;

    AssetRole(String folder) {
        this.folder = folder;
        this.prefix = folder + "/";
    }

    boolean accepts(String id) {
        return id.startsWith(prefix, id.indexOf(':') + 1);
    }
}
