package io.github.autyism.offlinewhitelist;

import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import io.github.autyism.offlinewhitelist.identity.Resolver;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;

/** The part of a login this mod adds, between the hello packet and vanilla's whitelist / ban checks. */
public final class LoginFlow {
	private static final AtomicInteger THREAD_ID = new AtomicInteger();
	private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
		Thread thread = new Thread(runnable, "Offline Whitelist login #" + THREAD_ID.incrementAndGet());
		thread.setDaemon(true);
		return thread;
	});

	private LoginFlow() {
	}

	/**
	 * Offline mode: works out the player's server UUID and skin on a worker thread (it may wait for Mojang),
	 * then hands the profile to {@code verify}, which continues the vanilla login, or kicks the player.
	 * Vanilla's online-mode authentication finishes on its own thread in the same way.
	 */
	public static void start(MinecraftServer server, String name, Consumer<GameProfile> verify, Consumer<Text> disconnect) {
		EXECUTOR.execute(() -> {
			try {
				Resolver.Outcome outcome = OfflineWhitelist.resolver().offlineLogin(name, uuid -> hasData(server, uuid));
				if (outcome instanceof Resolver.Join join) {
					OfflineWhitelist.LOGGER.info("[Offline Whitelist] {}: {}", name, join.log());
					verify.accept(profile(join));
				} else if (outcome instanceof Resolver.Kick kick) {
					OfflineWhitelist.LOGGER.info("[Offline Whitelist] {} can't join: {}", name, kick.log());
					disconnect.accept(Text.literal(kick.english() + "\n\n" + kick.chinese()));
				}
			} catch (Throwable t) {
				OfflineWhitelist.LOGGER.error("[Offline Whitelist] login of {} failed", name, t);
				disconnect.accept(Text.literal("Offline Whitelist could not finish your login. Please tell the server owner.\n\n"
						+ "登录时 Offline Whitelist 出错了，请告诉服主。"));
			}
		});
	}

	/** Online mode: the authenticated premium profile keeps its skin but gets the player's server UUID. */
	public static GameProfile remapOnline(MinecraftServer server, GameProfile profile) {
		if (profile.id() == null || profile.id().version() != 4) {
			return profile;
		}
		UUID uuid = OfflineWhitelist.resolver().onlineLogin(profile.id(), profile.name(), id -> hasData(server, id));
		if (uuid.equals(profile.id())) {
			return profile;
		}
		OfflineWhitelist.LOGGER.info("[Offline Whitelist] {}: premium account {}, server UUID {}", profile.name(), profile.id(), uuid);
		return new GameProfile(uuid, profile.name(), profile.properties());
	}

	static GameProfile profile(Resolver.Join join) {
		if (join.textures() == null) {
			return new GameProfile(join.uuid(), join.name());
		}
		Property textures = new Property("textures", join.textures().value(), join.textures().signature());
		return new GameProfile(join.uuid(), join.name(), new PropertyMap(ImmutableMultimap.of("textures", textures)));
	}

	static boolean hasData(MinecraftServer server, UUID uuid) {
		return Files.exists(server.getSavePath(WorldSavePath.PLAYERDATA).resolve(uuid + ".dat"));
	}
}
