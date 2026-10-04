package io.github.autyism.offlinewhitelist.identity;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The last answers from Mojang, in {@code config/offlinewhitelist/lookup-cache.json}. Only used when Mojang
 * can't be reached, so players who joined before can still get in (with their skin). Safe to delete.
 */
public final class LookupCache {
	private static final Logger LOGGER = LoggerFactory.getLogger("Offline Whitelist");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** {@code premium == null}: Mojang had no account with this name. */
	public record NameEntry(UUID premium, String name, Instant checked) {
	}

	private final Path file;
	private final Map<String, NameEntry> names = new HashMap<>();
	private final Map<UUID, Lookups.Textures> textures = new HashMap<>();

	private LookupCache(Path file) {
		this.file = file;
	}

	public static LookupCache inMemory() {
		return new LookupCache(null);
	}

	public static LookupCache load(Path file) {
		LookupCache cache = new LookupCache(file);
		if (!Files.exists(file)) {
			return cache;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
			for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("names").entrySet()) {
				JsonObject value = entry.getValue().getAsJsonObject();
				JsonElement premium = value.get("premium");
				boolean has = premium != null && !premium.isJsonNull();
				cache.names.put(entry.getKey(), new NameEntry(has ? UUID.fromString(premium.getAsString()) : null,
						has ? value.get("name").getAsString() : null, Instant.parse(value.get("checked").getAsString())));
			}
			for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("textures").entrySet()) {
				JsonObject value = entry.getValue().getAsJsonObject();
				cache.textures.put(UUID.fromString(entry.getKey()), new Lookups.Textures(value.get("value").getAsString(), value.get("signature").getAsString()));
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("[Offline Whitelist] {} could not be read and is ignored: {}", file, e.toString());
			cache.names.clear();
			cache.textures.clear();
		}
		return cache;
	}

	public synchronized Optional<NameEntry> name(String name) {
		return Optional.ofNullable(this.names.get(name.toLowerCase(Locale.ROOT)));
	}

	public synchronized void putName(String name, UUID premium, String exactName) {
		this.names.put(name.toLowerCase(Locale.ROOT), new NameEntry(premium, exactName, Instant.now()));
		this.save();
	}

	public synchronized Optional<Lookups.Textures> textures(UUID premium) {
		return Optional.ofNullable(premium == null ? null : this.textures.get(premium));
	}

	public synchronized void putTextures(UUID premium, Lookups.Textures textures) {
		if (!textures.equals(this.textures.put(premium, textures))) {
			this.save();
		}
	}

	private void save() {
		if (this.file == null) {
			return;
		}
		JsonObject root = new JsonObject();
		JsonObject names = new JsonObject();
		this.names.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
			JsonObject value = new JsonObject();
			NameEntry name = entry.getValue();
			value.add("premium", name.premium() == null ? JsonNull.INSTANCE : GSON.toJsonTree(name.premium().toString()));
			if (name.premium() != null) {
				value.addProperty("name", name.name());
			}
			value.addProperty("checked", name.checked().toString());
			names.add(entry.getKey(), value);
		});
		JsonObject textures = new JsonObject();
		this.textures.forEach((premium, texture) -> {
			JsonObject value = new JsonObject();
			value.addProperty("value", texture.value());
			value.addProperty("signature", texture.signature());
			textures.add(premium.toString(), value);
		});
		root.add("names", names);
		root.add("textures", textures);
		try {
			Files.createDirectories(this.file.getParent());
			Path temp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
			Files.writeString(temp, GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
			Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			LOGGER.warn("[Offline Whitelist] could not save {}: {}", this.file, e.toString());
		}
	}
}
