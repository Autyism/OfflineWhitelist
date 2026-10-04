package io.github.autyism.offlinewhitelist;

import java.util.Set;
import java.util.UUID;

import io.github.autyism.offlinewhitelist.identity.Person;
import net.minecraft.server.BannedPlayerEntry;
import net.minecraft.server.BannedPlayerList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.OperatorEntry;
import net.minecraft.server.OperatorList;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.Whitelist;
import net.minecraft.server.WhitelistEntry;

/**
 * Right before vanilla checks a joining premium player against the whitelist, the op list and the ban list:
 * if any of their UUIDs is on a list, all of them (server, premium, offline UUID of the current name) are
 * put on it. That way a player added under their premium UUID, or under the offline UUID of a new name, is
 * treated the same as under their server UUID, and the lists keep both kinds of UUID.
 */
public final class ListSync {
	private ListSync() {
	}

	public static void sync(MinecraftServer server, UUID uuid) {
		Person person = OfflineWhitelist.resolver().identities().byUuid(uuid).filter(p -> p.uuid().equals(uuid)).orElse(null);
		if (person == null) {
			return;
		}
		Set<UUID> all = person.allUuids();
		Set<UUID> keep = person.listUuids();

		Whitelist whitelist = server.getPlayerManager().getWhitelist();
		if (all.stream().anyMatch(id -> whitelist.isAllowed(entry(id, person)))) {
			for (UUID id : keep) {
				if (!whitelist.isAllowed(entry(id, person))) {
					whitelist.add(new WhitelistEntry(entry(id, person)));
					OfflineWhitelist.LOGGER.info("[Offline Whitelist] whitelisted {} under {} as well", person.name(), id);
				}
			}
		}

		OperatorList ops = server.getPlayerManager().getOpList();
		OperatorEntry best = null;
		for (UUID id : all) {
			OperatorEntry op = ops.get(entry(id, person));
			if (op != null && (best == null || op.getLevel().getLevel().getLevel() > best.getLevel().getLevel().getLevel())) {
				best = op;
			}
		}
		if (best != null) {
			for (UUID id : keep) {
				if (ops.get(entry(id, person)) == null) {
					ops.add(new OperatorEntry(entry(id, person), best.getLevel(), best.canBypassPlayerLimit()));
					OfflineWhitelist.LOGGER.info("[Offline Whitelist] made {} an operator under {} as well", person.name(), id);
				}
			}
		}

		BannedPlayerList bans = server.getPlayerManager().getUserBanList();
		BannedPlayerEntry ban = null;
		for (UUID id : all) {
			if (bans.contains(entry(id, person))) {
				ban = bans.get(entry(id, person));
				break;
			}
		}
		if (ban != null) {
			for (UUID id : keep) {
				if (!bans.contains(entry(id, person))) {
					bans.add(new BannedPlayerEntry(entry(id, person), ban.getCreationDate(), ban.getSource(), ban.getExpiryDate(), ban.getReason()));
					OfflineWhitelist.LOGGER.info("[Offline Whitelist] banned {} under {} as well", person.name(), id);
				}
			}
		}
	}

	static PlayerConfigEntry entry(UUID uuid, Person person) {
		return new PlayerConfigEntry(uuid, person.name());
	}
}
