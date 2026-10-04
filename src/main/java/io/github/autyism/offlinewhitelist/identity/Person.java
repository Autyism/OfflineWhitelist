package io.github.autyism.offlinewhitelist.identity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A premium (Mojang) account and the UUID it has on this server. The server UUID never changes, so the
 * player keeps their data when the account is renamed or the server switches between online and offline
 * mode. Instances are only changed through {@link Identities}, which guards them.
 */
public final class Person {
	private final UUID uuid;
	private final UUID premium;
	private String name;
	private final List<String> formerNames;

	Person(UUID uuid, UUID premium, String name, List<String> formerNames) {
		this.uuid = uuid;
		this.premium = premium;
		this.name = name;
		this.formerNames = new ArrayList<>(formerNames);
	}

	/** The UUID this player has on the server: their player data, stats and pets belong to it. */
	public UUID uuid() {
		return this.uuid;
	}

	public UUID premium() {
		return this.premium;
	}

	/** The name this player last joined with. */
	public String name() {
		return this.name;
	}

	public List<String> formerNames() {
		return Collections.unmodifiableList(this.formerNames);
	}

	/** The UUIDs kept in the whitelist, the op list and the ban list: server UUID, premium UUID and offline UUID of the current name. */
	public Set<UUID> listUuids() {
		Set<UUID> uuids = new LinkedHashSet<>();
		uuids.add(this.uuid);
		uuids.add(this.premium);
		uuids.add(OfflineUuids.of(this.name));
		return uuids;
	}

	/** Every UUID that belongs to this player, including the offline UUIDs of names they used before. */
	public Set<UUID> allUuids() {
		Set<UUID> uuids = this.listUuids();
		for (String former : this.formerNames) {
			uuids.add(OfflineUuids.of(former));
		}
		return uuids;
	}

	void rename(String newName) {
		if (!newName.equalsIgnoreCase(this.name)) {
			this.formerNames.removeIf(former -> former.equalsIgnoreCase(newName));
			this.formerNames.add(this.name);
		}
		this.name = newName;
	}
}
