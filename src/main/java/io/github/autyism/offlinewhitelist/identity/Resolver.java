package io.github.autyism.offlinewhitelist.identity;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Decides which UUID a player gets on this server.
 *
 * <ul>
 * <li>A premium account always gets the same server UUID. The first time it is seen, that is the UUID it
 * already has player data under: in offline mode the offline UUID of its name is tried first, in online mode
 * the premium UUID. Renaming the account or switching online-mode keeps it.</li>
 * <li>A name without a premium account gets the offline UUID of that name, as in vanilla, unless that UUID
 * belongs to a premium player who has since renamed: then the old name can no longer be used to join.</li>
 * <li>Mojang is never required. When it can't be reached, players already known here get their usual UUID
 * and their last known skin, and a name never seen before joins with its offline UUID, as in vanilla.
 * Without a known skin, the player simply has the default skin.</li>
 * </ul>
 *
 * <p>Nothing here touches Minecraft, so it can be tested on its own; {@code hasData} tells whether
 * player data exists for a UUID.
 */
public final class Resolver {
	/** Names Mojang accepts; anything else can't belong to a premium account, so there is no need to ask. */
	private static final Pattern MOJANG_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

	public sealed interface Outcome {
	}

	/** Join with this UUID and, when known, the signed skin. {@code log} says why, for the server log. */
	public record Join(UUID uuid, String name, UUID premium, Lookups.Textures textures, String log) implements Outcome {
	}

	public record Kick(String english, String chinese, String log) implements Outcome {
	}

	/**
	 * The player a command is about. {@code add} are the UUIDs to put on a list, {@code remove} every UUID to
	 * take off (the offline UUIDs of former names too).
	 */
	public record Target(String name, Set<UUID> add, Set<UUID> remove, Person person) {
	}

	private sealed interface Status {
	}

	private record Premium(UUID id, String name) implements Status {
	}

	private record NoAccount() implements Status {
	}

	private record Unknown(String why) implements Status {
	}

	private final Identities identities;
	private final LookupCache cache;
	private final Lookups api;

	public Resolver(Identities identities, LookupCache cache, Lookups api) {
		this.identities = identities;
		this.cache = cache;
		this.api = api;
	}

	public Identities identities() {
		return this.identities;
	}

	/** A player joining an offline-mode server. Asks Mojang (a few seconds at most), so call it off the server thread. */
	public Outcome offlineLogin(String name, Predicate<UUID> hasData) {
		UUID offline = OfflineUuids.of(name);
		// the skin of the account this name is expected to belong to downloads while Mojang is asked about the name
		UUID expected = this.identities.byCurrentName(name).map(Person::premium)
				.or(() -> this.cache.name(name).map(LookupCache.NameEntry::premium))
				.orElse(null);
		CompletableFuture<Lookups.Textures> early = expected == null ? null : CompletableFuture.supplyAsync(() -> this.textures(expected));
		Status status = this.status(name);
		Lookups.Textures textures = null;
		if (status instanceof Premium premium) {
			textures = premium.id().equals(expected) ? early.join() : this.textures(premium.id());
		}
		synchronized (this.identities) {
			if (status instanceof Premium premium) {
				Optional<Person> known = this.identities.byPremium(premium.id());
				if (known.isEmpty()) {
					UUID uuid = this.pickUuid(offline, premium.id(), false, hasData);
					Person person = this.identities.register(uuid, premium.id(), name);
					return new Join(person.uuid(), name, premium.id(), textures,
							"first join of premium account " + premium.id() + ", server UUID " + uuid + describe(uuid, offline, premium.id()) + skin(textures));
				}
				Person person = known.get();
				String former = person.name();
				boolean renamed = this.identities.rename(person, name);
				return new Join(person.uuid(), name, premium.id(), textures, "premium account " + premium.id() + ", server UUID " + person.uuid()
						+ (renamed ? " (renamed from " + former + ")" : "") + skin(textures));
			}
			Optional<Person> owner = this.identities.byUuid(offline);
			if (status instanceof NoAccount) {
				if (owner.isPresent()) {
					return retired(name, owner.get());
				}
				return new Join(offline, name, null, null, "no premium account, offline UUID " + offline);
			}
			String why = ((Unknown) status).why();
			if (owner.isPresent()) {
				Person person = owner.get();
				if (!person.name().equalsIgnoreCase(name)) {
					return retired(name, person);
				}
				Lookups.Textures known = early != null && person.premium().equals(expected) ? early.join()
						: this.cache.textures(person.premium()).orElse(null);
				return new Join(person.uuid(), name, person.premium(), known,
						"Mojang unreachable (" + why + "), known premium player, server UUID " + person.uuid() + skin(known));
			}
			return new Join(offline, name, null, null, "Mojang unreachable (" + why + "), offline UUID " + offline
					+ (hasData.test(offline) ? "" : " (first join)"));
		}
	}

	/** A premium player authenticated by an online-mode server: returns the UUID they get here. */
	public UUID onlineLogin(UUID premium, String name, Predicate<UUID> hasData) {
		synchronized (this.identities) {
			Optional<Person> known = this.identities.byPremium(premium);
			if (known.isPresent()) {
				this.identities.rename(known.get(), name);
				return known.get().uuid();
			}
			return this.identities.register(this.pickUuid(OfflineUuids.of(name), premium, true, hasData), premium, name).uuid();
		}
	}

	/** A name typed into a command. May ask Mojang. */
	public Target target(String typed) {
		Optional<Person> current = this.identities.byCurrentName(typed);
		if (current.isPresent()) {
			return of(current.get());
		}
		Status status = this.status(typed);
		if (status instanceof Premium premium) {
			Optional<Person> known = this.identities.byPremium(premium.id());
			if (known.isPresent()) {
				return of(known.get());
			}
			Set<UUID> uuids = new LinkedHashSet<>(List.of(OfflineUuids.of(typed), premium.id()));
			return new Target(typed, uuids, uuids, null);
		}
		Optional<Person> former = this.identities.byFormerName(typed);
		if (former.isPresent()) {
			return of(former.get());
		}
		Set<UUID> uuids = Set.of(OfflineUuids.of(typed));
		return new Target(typed, uuids, uuids, null);
	}

	/** A player picked by a selector such as {@code @a}: they are online, so their UUID is the server UUID. */
	public Target target(UUID uuid, String name) {
		Optional<Person> person = this.identities.byUuid(uuid);
		if (person.isPresent()) {
			return of(person.get());
		}
		Set<UUID> uuids = Set.of(uuid);
		return new Target(name, uuids, uuids, null);
	}

	/** Mojang's answer for a name, or the last answer when Mojang can't be reached. */
	public String describeStatus(String name) {
		Status status = this.status(name);
		if (status instanceof Premium premium) {
			return "premium account " + premium.id() + " (" + premium.name() + ")";
		}
		return status instanceof NoAccount ? "no premium account" : "unknown, Mojang can't be reached (" + ((Unknown) status).why() + ")";
	}

	private static Target of(Person person) {
		return new Target(person.name(), person.listUuids(), person.allUuids(), person);
	}

	private Status status(String name) {
		if (!MOJANG_NAME.matcher(name).matches()) {
			return new NoAccount();
		}
		Lookups.Lookup lookup = this.api.lookup(name);
		if (lookup instanceof Lookups.Found found) {
			this.cache.putName(name, found.id(), found.name());
			return new Premium(found.id(), found.name());
		}
		if (lookup instanceof Lookups.NotFound) {
			this.cache.putName(name, null, null);
			return new NoAccount();
		}
		String why = ((Lookups.Unavailable) lookup).why();
		return this.cache.name(name)
				.<Status>map(entry -> entry.premium() == null ? new NoAccount() : new Premium(entry.premium(), entry.name()))
				.orElseGet(() -> new Unknown(why));
	}

	private Lookups.Textures textures(UUID premium) {
		Optional<Lookups.Textures> fresh = this.api.textures(premium);
		if (fresh.isPresent()) {
			this.cache.putTextures(premium, fresh.get());
			return fresh.get();
		}
		return this.cache.textures(premium).orElse(null);
	}

	/**
	 * The server UUID for a premium account seen for the first time: whichever of its natural UUID (offline
	 * UUID in offline mode, premium UUID in online mode) and the other one already has player data, else the
	 * natural one. A UUID that belongs to another player is never reused.
	 */
	private UUID pickUuid(UUID offline, UUID premium, boolean online, Predicate<UUID> hasData) {
		List<UUID> free = Stream.of(online ? premium : offline, online ? offline : premium)
				.filter(uuid -> this.identities.byUuid(uuid).isEmpty())
				.toList();
		for (UUID uuid : free) {
			if (hasData.test(uuid)) {
				return uuid;
			}
		}
		return free.isEmpty() ? premium : free.getFirst();
	}

	private static Kick retired(String name, Person owner) {
		return new Kick("The name " + name + " belonged to another player of this server, so it can't be used to join.",
				"名字 " + name + " 以前属于本服的另一位玩家，不能再用它进服。",
				name + " is a former name of premium account " + owner.premium() + ", now " + owner.name());
	}

	private static String describe(UUID uuid, UUID offline, UUID premium) {
		return uuid.equals(offline) ? " (the offline UUID of the name)" : uuid.equals(premium) ? " (the premium UUID)" : "";
	}

	private static String skin(Lookups.Textures textures) {
		return textures == null ? ", no skin" : ", with skin";
	}
}
