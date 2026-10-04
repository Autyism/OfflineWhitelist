package io.github.autyism.offlinewhitelist.clienttest;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.command.permission.LeveledPermissionPredicate;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.util.AssetInfo;

/**
 * Joins the server given by {@code --quickPlayMultiplayer} under {@code --username}, then writes what it saw to
 * {@code <owtest.out>/<name>.json}: the UUID the server gave, permission level, position, inventory size and
 * the skin and cape the client loaded. Screenshots: front and back in third person, and the player list. When
 * the server refuses the login, the reason and a screenshot of the disconnect screen. Then the game closes.
 */
public final class ClientTest implements ClientModInitializer {
	private static final int TIMEOUT_TICKS = 20 * 150;

	private final Map<String, Object> result = new LinkedHashMap<>();
	private Path out;
	private String name;
	private int ticks;
	private int inWorld;
	private int disconnected;
	private boolean done;

	@Override
	public void onInitializeClient() {
		this.out = Path.of(System.getProperty("owtest.out", "owtest")).toAbsolutePath();
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}

	private void tick(MinecraftClient client) {
		if (this.done) {
			return;
		}
		if (this.name == null) {
			this.name = client.getSession().getUsername();
			this.result.put("name", this.name);
		}
		this.ticks++;
		if (client.currentScreen instanceof DisconnectedScreen screen && client.player == null) {
			if (++this.disconnected == 1) {
				this.result.put("kicked", reason(screen));
			} else if (this.disconnected == 20) {
				this.screenshot(client, "kicked");
			} else if (this.disconnected == 30) {
				this.finish(client);
			}
			return;
		}
		if (client.player != null && client.world != null) {
			this.inWorld++;
			switch (this.inWorld) {
				case 100 -> {
					this.record(client);
					client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
					client.options.hudHidden = true;
				}
				case 160 -> this.screenshot(client, "front");
				case 165 -> client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
				case 205 -> this.screenshot(client, "back");
				case 210 -> {
					client.options.setPerspective(Perspective.FIRST_PERSON);
					client.options.hudHidden = false;
					client.options.playerListKey.setPressed(true);
				}
				case 240 -> this.screenshot(client, "players");
				case 245 -> {
					client.options.playerListKey.setPressed(false);
					this.recordSkin(client);
					this.finish(client);
				}
				default -> {
				}
			}
		}
		if (this.ticks > TIMEOUT_TICKS) {
			this.result.put("timeout", "no world and no disconnect screen after " + this.ticks + " ticks; screen " + client.currentScreen);
			this.finish(client);
		}
	}

	private void record(MinecraftClient client) {
		this.result.put("uuid", client.player.getUuid().toString());
		this.result.put("profileName", client.player.getGameProfile().name());
		this.result.put("profileHasTextures", client.player.getGameProfile().properties().containsKey("textures"));
		this.result.put("permissionLevel", client.player.getPermissions() instanceof LeveledPermissionPredicate leveled
				? leveled.getLevel().getLevel() : client.player.getPermissions().toString());
		this.result.put("gameMode", String.valueOf(client.interactionManager.getCurrentGameMode()));
		this.result.put("dimension", client.world.getRegistryKey().getValue().toString());
		this.result.put("position", client.player.getBlockPos().toShortString());
		int stacks = 0;
		for (int i = 0; i < client.player.getInventory().size(); i++) {
			if (!client.player.getInventory().getStack(i).isEmpty()) {
				stacks++;
			}
		}
		this.result.put("inventoryStacks", stacks);
		this.result.put("mainHand", client.player.getMainHandStack().toString());
	}

	private void recordSkin(MinecraftClient client) {
		PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
		if (entry == null) {
			this.result.put("skin", "no player list entry");
			return;
		}
		SkinTextures skin = entry.getSkinTextures();
		this.result.put("skinBody", skin.body() instanceof AssetInfo.SkinAssetInfo downloaded ? downloaded.url() : "default: " + skin.body());
		this.result.put("skinCape", skin.cape() == null ? null : skin.cape() instanceof AssetInfo.SkinAssetInfo cape ? cape.url() : skin.cape().toString());
		this.result.put("skinModel", String.valueOf(skin.model()));
		this.result.put("skinSecure", skin.secure());
		List<String> others = new ArrayList<>();
		for (PlayerListEntry other : client.getNetworkHandler().getPlayerList()) {
			others.add(other.getProfile().name() + " " + other.getProfile().id());
		}
		this.result.put("playerList", others);
	}

	private static String reason(DisconnectedScreen screen) {
		for (Field field : DisconnectedScreen.class.getDeclaredFields()) {
			if (field.getType() == DisconnectionInfo.class) {
				try {
					field.setAccessible(true);
					return ((DisconnectionInfo) field.get(screen)).reason().getString();
				} catch (ReflectiveOperationException e) {
					return "could not read: " + e;
				}
			}
		}
		return screen.getTitle().getString();
	}

	private void screenshot(MinecraftClient client, String what) {
		String file = this.name + "-" + what + ".png";
		ScreenshotRecorder.saveScreenshot(client.runDirectory, file, client.getFramebuffer(), 1, message -> {
		});
		this.result.put("screenshot-" + what, client.runDirectory.toPath().resolve("screenshots").resolve(file).toString());
	}

	private void finish(MinecraftClient client) {
		this.done = true;
		try {
			Files.createDirectories(this.out);
			Files.writeString(this.out.resolve(this.name + ".json"),
					new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create().toJson(this.result), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		client.scheduleStop();
	}
}
