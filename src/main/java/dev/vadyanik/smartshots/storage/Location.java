package dev.vadyanik.smartshots.storage;

import java.nio.file.Path;

/**
 * Where a screenshot belongs.
 *
 * @param category      singleplayer / multiplayer / realms / other
 * @param instanceDir   folder of the world (singleplayer) or the server (multiplayer)
 * @param instanceName  human readable world / server name
 * @param locationDir   sub folder inside the instance (dimension or server sub-world)
 * @param locationName  human readable sub-world name
 */
public record Location(Category category, Path instanceDir, String instanceName, Path locationDir, String locationName) {
}
