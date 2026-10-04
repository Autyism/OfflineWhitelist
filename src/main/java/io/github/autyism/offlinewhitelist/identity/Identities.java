package io.github.autyism.offlinewhitelist.identity;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Which server UUID each premium account has, saved in {@code config/offlinewhitelist/identities.json}.
 *
 * <p>All methods lock this object. Code that reads and then registers (the login logic) holds the lock
 * across both steps, so two logins of the same new account can't register it twice.
 */
public final class Identities {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final String ABOUT = "Offline Whitelist: the UUID each premium (Mojang) account has on this server. "
			+ "Players are matched by their premium UUID, so a renamed account or a switch between online and offline mode keeps the same player data. "
			+ "Edit only while the server is stopped.";

	private final Path file;
	private final List<Person> people = new ArrayList<>();

	private Identities(Path file) {
		this.file = file;
	}

	/** An empty list that is never saved, for tests. */
	public static Identities inMemory() {
		return new Identities(null);
	}

	/**
	 * Reads the file, or starts empty when it does not exist. A file that can't be read stops the server
	 * on purpose: starting without it would give premium players new UUIDs and so new, empty player data.
	 */
	public static Identities load(Path file) {
		Identities identities = new Identities(file);
		if (!Files.exists(file)) {
			return identities;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
			for (JsonElement element : root.getAsJsonArray("players")) {
				JsonObject player = element.getAsJsonObject();
				List<String> former = new ArrayList<>();
				if (player.has("formerNames")) {
					player.getAsJsonArray("formerNames").forEach(name -> former.add(name.getAsString()));
				}
				identities.people.add(new Person(UUID.fromString(player.get("uuid").getAsString()),
						UUID.fromString(player.get("premium").getAsString()), player.get("name").getAsString(), former));
			}
		} catch (IOException | RuntimeException e) {
			throw new IllegalStateException(file + " could not be read (" + e.getMessage() + "). Fix the file, or restore "
					+ file.getFileName() + ".bak from the same folder. Deleting it makes every premium player start with new player data.", e);
		}
		return identities;
	}

	public synchronized Optional<Person> byPremium(UUID premium) {
		return this.people.stream().filter(person -> person.premium().equals(premium)).findFirst();
	}

	/** The player any of whose UUIDs (server, premium, offline UUID of a current or former name) is this one. */
	public synchronized Optional<Person> byUuid(UUID uuid) {
		for (Person person : this.people) {
			if (person.uuid().equals(uuid)) {
				return Optional.of(person);
			}
		}
		return this.people.stream().filter(person -> person.allUuids().contains(uuid)).findFirst();
	}

	public synchronized Optional<Person> byCurrentName(String name) {
		return this.people.stream().filter(person -> person.name().equalsIgnoreCase(name)).findFirst();
	}

	public synchronized Optional<Person> byFormerName(String name) {
		return this.people.stream().filter(person -> person.formerNames().stream().anyMatch(name::equalsIgnoreCase)).findFirst();
	}

	public synchronized List<Person> all() {
		return List.copyOf(this.people);
	}

	public synchronized Person register(UUID uuid, UUID premium, String name) {
		Person person = new Person(uuid, premium, name, List.of());
		this.people.add(person);
		this.save();
		return person;
	}

	/** Records the name the player joined with; returns true when it differs from the last one (not counting case). */
	public synchronized boolean rename(Person person, String name) {
		if (person.name().equals(name)) {
			return false;
		}
		boolean renamed = !person.name().equalsIgnoreCase(name);
		person.rename(name);
		this.save();
		return renamed;
	}

	private void save() {
		if (this.file == null) {
			return;
		}
		JsonObject root = new JsonObject();
		root.addProperty("about", ABOUT);
		JsonArray players = new JsonArray();
		for (Person person : this.people) {
			JsonObject player = new JsonObject();
			player.addProperty("name", person.name());
			player.addProperty("uuid", person.uuid().toString());
			player.addProperty("premium", person.premium().toString());
			if (!person.formerNames().isEmpty()) {
				JsonArray former = new JsonArray();
				person.formerNames().forEach(former::add);
				player.add("formerNames", former);
			}
			players.add(player);
		}
		root.add("players", players);
		try {
			Files.createDirectories(this.file.getParent());
			Path temp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
			Files.writeString(temp, GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
			if (Files.exists(this.file)) {
				Files.copy(this.file, this.file.resolveSibling(this.file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
			}
			Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			throw new IllegalStateException("Could not save " + this.file, e);
		}
	}
}
