package io.github.autyism.offlinewhitelist.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResolverTest {
	private static final UUID P1 = UUID.fromString("7a96e458-a697-4f90-a04d-440afd386431");
	private static final UUID P2 = UUID.fromString("0b7c2a51-1111-4abc-8def-123456789abc");
	private static final Lookups.Textures SKIN = new Lookups.Textures("dmFsdWU=", "c2lnbmF0dXJl");

	/** Mojang as a map; {@code down} makes every call fail like a timeout. */
	static final class FakeMojang implements Lookups {
		final Map<String, UUID> accounts = new HashMap<>();
		final Map<UUID, Textures> skins = new HashMap<>();
		boolean down;
		int lookups;

		@Override
		public Lookup lookup(String name) {
			this.lookups++;
			if (this.down) {
				return new Unavailable("HttpConnectTimeoutException");
			}
			for (Map.Entry<String, UUID> account : this.accounts.entrySet()) {
				if (account.getKey().equalsIgnoreCase(name)) {
					return new Found(account.getValue(), account.getKey());
				}
			}
			return new NotFound();
		}

		@Override
		public Optional<Textures> textures(UUID premium) {
			return this.down ? Optional.empty() : Optional.ofNullable(this.skins.get(premium));
		}
	}

	FakeMojang mojang;
	Identities identities;
	LookupCache cache;
	Resolver resolver;
	Set<UUID> data;

	@BeforeEach
	void setUp() {
		this.mojang = new FakeMojang();
		this.identities = Identities.inMemory();
		this.cache = LookupCache.inMemory();
		this.resolver = new Resolver(this.identities, this.cache, this.mojang);
		this.data = new HashSet<>();
	}

	Resolver.Outcome join(String name) {
		return this.resolver.offlineLogin(name, this.data::contains);
	}

	Resolver.Join joined(String name) {
		return assertInstanceOf(Resolver.Join.class, this.join(name));
	}

	@Test
	void offlineUuidMatchesVanilla() {
		// the values the real server has for these names
		assertEquals(UUID.fromString("001a2aa7-354e-3ea9-a966-83e63af15a2d"), OfflineUuids.of("AutyistickDildih"));
		assertEquals(UUID.fromString("d860110a-7bfa-3f2a-9bd7-655b23b5efa0"), OfflineUuids.of("Autyism"));
	}

	@Test
	void firstJoinKeepsExistingOfflineData() {
		this.mojang.accounts.put("Steve123", P1);
		this.mojang.skins.put(P1, SKIN);
		this.data.add(OfflineUuids.of("Steve123"));
		this.data.add(P1);
		Resolver.Join join = this.joined("Steve123");
		assertEquals(OfflineUuids.of("Steve123"), join.uuid());
		assertEquals(P1, join.premium());
		assertEquals(SKIN, join.textures());
		assertEquals(OfflineUuids.of("Steve123"), this.identities.byPremium(P1).orElseThrow().uuid());
	}

	@Test
	void firstJoinUsesPremiumDataWhenThatIsAllThereIs() {
		this.mojang.accounts.put("Steve123", P1);
		this.data.add(P1);
		assertEquals(P1, this.joined("Steve123").uuid());
	}

	@Test
	void firstJoinWithoutDataUsesTheOfflineUuidInOfflineMode() {
		this.mojang.accounts.put("Steve123", P1);
		Resolver.Join join = this.joined("Steve123");
		assertEquals(OfflineUuids.of("Steve123"), join.uuid());
		assertNull(join.textures());
	}

	@Test
	void renamedAccountKeepsItsServerUuid() {
		this.mojang.accounts.put("AutyistickDildih", P1);
		this.data.add(OfflineUuids.of("AutyistickDildih"));
		UUID server = this.joined("AutyistickDildih").uuid();

		this.mojang.accounts.clear();
		this.mojang.accounts.put("Autyism", P1);
		Resolver.Join join = this.joined("Autyism");
		assertEquals(server, join.uuid());
		Person person = this.identities.byPremium(P1).orElseThrow();
		assertEquals("Autyism", person.name());
		assertEquals(List.of("AutyistickDildih"), person.formerNames());
		assertTrue(join.log().contains("renamed from AutyistickDildih"), join.log());
	}

	@Test
	void formerNameCanNotBeUsedByAnOfflinePlayer() {
		this.identities.register(OfflineUuids.of("AutyistickDildih"), P1, "AutyistickDildih");
		this.identities.rename(this.identities.byPremium(P1).orElseThrow(), "Autyism");
		this.mojang.accounts.put("Autyism", P1);
		this.mojang.accounts.put("Other", P2);
		// the old name has no premium account any more
		Resolver.Kick kick = assertInstanceOf(Resolver.Kick.class, this.join("AutyistickDildih"));
		assertTrue(kick.english().contains("belonged to another player"), kick.english());
	}

	@Test
	void staleNameIsRetiredWhenMojangNoLongerKnowsIt() {
		// registered under its old name, and the account was renamed before joining again
		this.identities.register(OfflineUuids.of("OldName"), P1, "OldName");
		assertInstanceOf(Resolver.Kick.class, this.join("OldName"));
	}

	@Test
	void formerNameTakenByAnotherPremiumAccountGetsItsOwnUuid() {
		this.identities.register(OfflineUuids.of("OldName"), P1, "NewName");
		this.mojang.accounts.put("OldName", P2);
		this.data.add(P2);
		Resolver.Join join = this.joined("OldName");
		assertNotEquals(OfflineUuids.of("OldName"), join.uuid());
		assertEquals(P2, join.uuid());
	}

	@Test
	void formerNameTakenByANewPremiumAccountWithoutDataStillGetsItsOwnUuid() {
		this.identities.register(OfflineUuids.of("OldName"), P1, "NewName");
		this.mojang.accounts.put("OldName", P2);
		assertEquals(P2, this.joined("OldName").uuid());
	}

	@Test
	void nameWithoutPremiumAccountUsesItsOfflineUuid() {
		Resolver.Join join = this.joined("cracked_kid");
		assertEquals(OfflineUuids.of("cracked_kid"), join.uuid());
		assertNull(join.premium());
		assertTrue(this.identities.all().isEmpty());
	}

	@Test
	void namesMojangCanNotHaveAreNotLookedUp() {
		Resolver.Join join = this.joined("a.b-c");
		assertEquals(OfflineUuids.of("a.b-c"), join.uuid());
		assertEquals(0, this.mojang.lookups);
	}

	@Test
	void mojangDownUsesTheLastAnswer() {
		this.mojang.accounts.put("Steve123", P1);
		this.mojang.skins.put(P1, SKIN);
		UUID server = this.joined("Steve123").uuid();
		this.mojang.down = true;
		Resolver.Join join = this.joined("Steve123");
		assertEquals(server, join.uuid());
		assertEquals(SKIN, join.textures());
	}

	@Test
	void mojangDownKnownPlayerWithoutCacheEntry() {
		this.identities.register(OfflineUuids.of("Steve123"), P1, "Steve123");
		this.mojang.down = true;
		assertEquals(OfflineUuids.of("Steve123"), this.joined("Steve123").uuid());
	}

	@Test
	void mojangDownKnownCrackedNameStillWorks() {
		this.joined("cracked_kid");
		this.mojang.down = true;
		assertEquals(OfflineUuids.of("cracked_kid"), this.joined("cracked_kid").uuid());
	}

	@Test
	void mojangDownExistingPlayerFromBeforeTheModCanJoin() {
		this.mojang.down = true;
		this.data.add(OfflineUuids.of("Veteran"));
		assertEquals(OfflineUuids.of("Veteran"), this.joined("Veteran").uuid());
	}

	@Test
	void mojangDownNewNameStillJoinsWithItsOfflineUuid() {
		this.mojang.down = true;
		Resolver.Join join = this.joined("Newcomer");
		assertEquals(OfflineUuids.of("Newcomer"), join.uuid());
		assertNull(join.textures(), "no skin known: the default skin");
		assertTrue(this.identities.all().isEmpty());
	}

	@Test
	void mojangDownKnownPlayerWithoutCachedSkinHasTheDefaultSkin() {
		this.identities.register(OfflineUuids.of("Steve123"), P1, "Steve123");
		this.mojang.down = true;
		Resolver.Join join = this.joined("Steve123");
		assertEquals(OfflineUuids.of("Steve123"), join.uuid());
		assertNull(join.textures());
	}

	@Test
	void onlyTheSkinServiceDownStillFindsThePlayer() {
		this.mojang.accounts.put("Steve123", P1);
		this.data.add(OfflineUuids.of("Steve123"));
		Resolver.Join join = this.joined("Steve123");
		assertEquals(OfflineUuids.of("Steve123"), join.uuid());
		assertNull(join.textures());
	}

	@Test
	void skinOfAKnownPlayerIsFetchedWhileTheNameIsChecked() {
		this.identities.register(OfflineUuids.of("Steve123"), P1, "Steve123");
		this.mojang.accounts.put("Steve123", P1);
		this.mojang.skins.put(P1, SKIN);
		assertEquals(SKIN, this.joined("Steve123").textures());
	}

	@Test
	void mojangDownFormerNameIsStillRetired() {
		this.identities.register(OfflineUuids.of("AutyistickDildih"), P1, "AutyistickDildih");
		this.identities.rename(this.identities.byPremium(P1).orElseThrow(), "Autyism");
		this.mojang.down = true;
		assertInstanceOf(Resolver.Kick.class, this.join("AutyistickDildih"));
		assertEquals(OfflineUuids.of("AutyistickDildih"), this.joined("Autyism").uuid());
	}

	@Test
	void skinFromCacheWhenSessionServerFails() {
		this.mojang.accounts.put("Steve123", P1);
		this.mojang.skins.put(P1, SKIN);
		this.joined("Steve123");
		this.mojang.skins.clear();
		assertEquals(SKIN, this.joined("Steve123").textures());
	}

	@Test
	void onlineModeUsesTheSameServerUuid() {
		this.mojang.accounts.put("Steve123", P1);
		this.data.add(OfflineUuids.of("Steve123"));
		UUID server = this.joined("Steve123").uuid();
		assertEquals(server, this.resolver.onlineLogin(P1, "Steve123", this.data::contains));
		assertEquals(server, this.resolver.onlineLogin(P1, "Renamed", this.data::contains));
		assertEquals("Renamed", this.identities.byPremium(P1).orElseThrow().name());
	}

	@Test
	void onlineModeFirstJoinPrefersThePremiumUuid() {
		assertEquals(P1, this.resolver.onlineLogin(P1, "Steve123", this.data::contains));
	}

	@Test
	void onlineModeFirstJoinKeepsOfflineData() {
		this.data.add(OfflineUuids.of("Steve123"));
		assertEquals(OfflineUuids.of("Steve123"), this.resolver.onlineLogin(P1, "Steve123", this.data::contains));
	}

	@Test
	void commandTargets() {
		this.identities.register(OfflineUuids.of("AutyistickDildih"), P1, "AutyistickDildih");
		this.identities.rename(this.identities.byPremium(P1).orElseThrow(), "Autyism");
		this.mojang.accounts.put("Autyism", P1);
		this.mojang.accounts.put("Notch", P2);

		Resolver.Target known = this.resolver.target("autyism");
		assertEquals("Autyism", known.name());
		assertEquals(Set.of(OfflineUuids.of("AutyistickDildih"), P1, OfflineUuids.of("Autyism")), known.add());
		assertTrue(known.remove().containsAll(known.add()));
		assertEquals(0, this.mojang.lookups, "a known current name needs no lookup");

		assertEquals(known.person(), this.resolver.target("AutyistickDildih").person(), "a former name means the same player");

		Resolver.Target premium = this.resolver.target("Notch");
		assertNull(premium.person());
		assertEquals(Set.of(OfflineUuids.of("Notch"), P2), premium.add());

		Resolver.Target cracked = this.resolver.target("cracked_kid");
		assertEquals(Set.of(OfflineUuids.of("cracked_kid")), cracked.add());

		assertEquals(known.person(), this.resolver.target(OfflineUuids.of("AutyistickDildih"), "Autyism").person());
	}

	@Test
	void namesAreCachedCaseInsensitively() {
		this.mojang.accounts.put("Steve123", P1);
		this.joined("Steve123");
		assertEquals(P1, this.cache.name("STEVE123".toLowerCase(Locale.ROOT)).orElseThrow().premium());
	}

	@Test
	void identitiesSurviveARestart(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("identities.json");
		Identities saved = Identities.load(file);
		Person person = saved.register(OfflineUuids.of("AutyistickDildih"), P1, "AutyistickDildih");
		saved.rename(person, "Autyism");
		Identities loaded = Identities.load(file);
		Person again = loaded.byPremium(P1).orElseThrow();
		assertEquals(OfflineUuids.of("AutyistickDildih"), again.uuid());
		assertEquals("Autyism", again.name());
		assertEquals(List.of("AutyistickDildih"), again.formerNames());
		assertTrue(Files.exists(dir.resolve("identities.json.bak")), "the previous version is kept");
	}

	@Test
	void brokenIdentitiesFileStopsTheServer(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("identities.json");
		Files.writeString(file, "{ \"players\": [ { \"name\": \"x\" ");
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> Identities.load(file));
		assertTrue(e.getMessage().contains("could not be read"), e.getMessage());
	}

	@Test
	void lookupCacheSurvivesARestart(@TempDir Path dir) {
		Path file = dir.resolve("lookup-cache.json");
		LookupCache saved = LookupCache.load(file);
		saved.putName("Steve123", P1, "Steve123");
		saved.putName("cracked_kid", null, null);
		saved.putTextures(P1, SKIN);
		LookupCache loaded = LookupCache.load(file);
		assertEquals(P1, loaded.name("steve123").orElseThrow().premium());
		assertNull(loaded.name("cracked_kid").orElseThrow().premium());
		assertEquals(SKIN, loaded.textures(P1).orElseThrow());
	}
}
