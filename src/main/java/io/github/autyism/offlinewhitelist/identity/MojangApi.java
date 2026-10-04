package io.github.autyism.offlinewhitelist.identity;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Asks Mojang who owns a name and for the signed skin of an account, and never makes a login wait long:
 * every question gives up after about 3 seconds, and once Mojang has failed it is not asked again for two
 * minutes (the cached answers are used instead). The base addresses can be changed with the system
 * properties {@code offlinewhitelist.api}, {@code offlinewhitelist.services} and
 * {@code offlinewhitelist.sessionserver}, which is how an outage is tested.
 */
public final class MojangApi implements Lookups {
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
	private static final long PAUSE_AFTER_FAILURE_MS = 120_000;

	private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build();
	private final String api = System.getProperty("offlinewhitelist.api", "https://api.mojang.com");
	private final String services = System.getProperty("offlinewhitelist.services", "https://api.minecraftservices.com");
	private final String sessionServer = System.getProperty("offlinewhitelist.sessionserver", "https://sessionserver.mojang.com");
	/** Until when name lookups and skin downloads are skipped after a failure (System.currentTimeMillis()). */
	private volatile long namesPausedUntil;
	private volatile long skinsPausedUntil;

	@Override
	public Lookup lookup(String name) {
		long now = System.currentTimeMillis();
		if (now < this.namesPausedUntil) {
			return new Unavailable("Mojang failed recently, not asking again for " + (this.namesPausedUntil - now) / 1000 + " s");
		}
		String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8);
		// both of Mojang's name services at once; the first real answer counts
		List<CompletableFuture<Lookup>> asks = List.of(
				this.lookupAt(this.api + "/users/profiles/minecraft/" + encoded),
				this.lookupAt(this.services + "/minecraft/profile/lookup/name/" + encoded));
		CompletableFuture<Lookup> answer = new CompletableFuture<>();
		AtomicInteger failed = new AtomicInteger();
		StringBuffer why = new StringBuffer();
		for (CompletableFuture<Lookup> ask : asks) {
			ask.thenAccept(lookup -> {
				if (lookup instanceof Unavailable unavailable) {
					synchronized (why) {
						why.append(why.isEmpty() ? "" : "; ").append(unavailable.why());
					}
					if (failed.incrementAndGet() == asks.size()) {
						answer.complete(new Unavailable(why.toString()));
					}
				} else {
					answer.complete(lookup);
				}
			});
		}
		Lookup lookup;
		try {
			lookup = answer.get(REQUEST_TIMEOUT.toMillis() + 500, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			lookup = new Unavailable("interrupted");
		} catch (Exception e) {
			lookup = new Unavailable("no answer within " + REQUEST_TIMEOUT.toSeconds() + " s");
		}
		if (lookup instanceof Unavailable) {
			this.namesPausedUntil = System.currentTimeMillis() + PAUSE_AFTER_FAILURE_MS;
			asks.forEach(ask -> ask.cancel(true));
		}
		return lookup;
	}

	private CompletableFuture<Lookup> lookupAt(String url) {
		String host = URI.create(url).getHost();
		return this.get(url).handle((response, error) -> {
			if (error != null) {
				return new Unavailable(rootCause(error) + " from " + host);
			}
			int status = response.statusCode();
			if (status == 200) {
				try {
					JsonObject profile = JsonParser.parseString(response.body()).getAsJsonObject();
					return new Found(parseUuid(profile.get("id").getAsString()), profile.get("name").getAsString());
				} catch (RuntimeException e) {
					return new Unavailable("unreadable answer from " + host);
				}
			}
			if (status == 204 || status == 404) {
				return new NotFound();
			}
			return new Unavailable("HTTP " + status + " from " + host);
		});
	}

	@Override
	public Optional<Textures> textures(UUID premium) {
		if (System.currentTimeMillis() < this.skinsPausedUntil) {
			return Optional.empty();
		}
		String url = this.sessionServer + "/session/minecraft/profile/" + premium.toString().replace("-", "") + "?unsigned=false";
		try {
			HttpResponse<String> response = this.get(url).get(REQUEST_TIMEOUT.toMillis() + 500, TimeUnit.MILLISECONDS);
			if (response.statusCode() != 200) {
				if (response.statusCode() != 204 && response.statusCode() != 404) {
					this.skinsPausedUntil = System.currentTimeMillis() + PAUSE_AFTER_FAILURE_MS;
				}
				return Optional.empty();
			}
			for (JsonElement element : JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("properties")) {
				JsonObject property = element.getAsJsonObject();
				if ("textures".equals(property.get("name").getAsString()) && property.has("signature")) {
					return Optional.of(new Textures(property.get("value").getAsString(), property.get("signature").getAsString()));
				}
			}
			return Optional.empty();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		} catch (Exception e) {
			this.skinsPausedUntil = System.currentTimeMillis() + PAUSE_AFTER_FAILURE_MS;
			return Optional.empty();
		}
	}

	private CompletableFuture<HttpResponse<String>> get(String url) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT).header("Accept", "application/json").GET().build();
		return this.client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
	}

	private static String rootCause(Throwable error) {
		Throwable cause = error;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause.getClass().getSimpleName();
	}

	static UUID parseUuid(String id) {
		if (id.length() == 32) {
			id = id.substring(0, 8) + "-" + id.substring(8, 12) + "-" + id.substring(12, 16) + "-" + id.substring(16, 20) + "-" + id.substring(20);
		}
		return UUID.fromString(id);
	}
}
