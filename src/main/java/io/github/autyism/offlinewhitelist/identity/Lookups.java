package io.github.autyism.offlinewhitelist.identity;

import java.util.Optional;
import java.util.UUID;

/** What the login logic asks Mojang. {@link MojangApi} is the real one; tests use a fake. */
public interface Lookups {
	/** Who owns this name at Mojang right now. */
	Lookup lookup(String name);

	/** The signed skin and cape of a premium account, as the session server hands it out. */
	Optional<Textures> textures(UUID premium);

	sealed interface Lookup {
	}

	record Found(UUID id, String name) implements Lookup {
	}

	record NotFound() implements Lookup {
	}

	record Unavailable(String why) implements Lookup {
	}

	/** The {@code textures} profile property: base64 value plus Mojang's signature, so clients accept it. */
	record Textures(String value, String signature) {
	}
}
