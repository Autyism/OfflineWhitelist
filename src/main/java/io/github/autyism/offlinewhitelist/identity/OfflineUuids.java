package io.github.autyism.offlinewhitelist.identity;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class OfflineUuids {
	private OfflineUuids() {
	}

	/** The UUID an offline-mode server gives a player of exactly this name (the same as vanilla computes). */
	public static UUID of(String name) {
		return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
	}
}
