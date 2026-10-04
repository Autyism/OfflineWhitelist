package io.github.autyism.offlinewhitelist;

import java.nio.file.Path;

import io.github.autyism.offlinewhitelist.identity.Identities;
import io.github.autyism.offlinewhitelist.identity.LookupCache;
import io.github.autyism.offlinewhitelist.identity.MojangApi;
import io.github.autyism.offlinewhitelist.identity.Resolver;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes offline-mode servers treat premium players properly:
 * <ul>
 * <li>each premium account keeps one UUID on the server, so renaming the account or switching online-mode
 * keeps its player data ({@link io.github.autyism.offlinewhitelist.identity.Resolver});</li>
 * <li>premium players get their skin and cape, signed by Mojang ({@link LoginFlow});</li>
 * <li>whitelist, op, ban and pardon act on every UUID of a player ({@link Commands}, {@link ListSync}).</li>
 * </ul>
 */
public class OfflineWhitelist implements DedicatedServerModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("Offline Whitelist");
	private static Resolver resolver;

	public static Resolver resolver() {
		return resolver;
	}

	@Override
	public void onInitializeServer() {
		Path dir = FabricLoader.getInstance().getConfigDir().resolve("offlinewhitelist");
		// a broken identities file stops the server here, before anyone can join with the wrong UUID
		Identities identities = Identities.load(dir.resolve("identities.json"));
		LookupCache cache = LookupCache.load(dir.resolve("lookup-cache.json"));
		resolver = new Resolver(identities, cache, new MojangApi());
		LOGGER.info("[Offline Whitelist] {} premium players known", identities.all().size());
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			if (environment.dedicated) {
				Commands.register(dispatcher);
			}
		});
	}
}
