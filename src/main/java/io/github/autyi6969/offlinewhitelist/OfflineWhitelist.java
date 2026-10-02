package io.github.autyi6969.offlinewhitelist;

import java.util.Collection;
import java.util.List;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.autyi6969.offlinewhitelist.mixin.WhitelistCommandInvoker;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.GameProfileArgumentType;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.util.StringHelper;

/**
 * In offline mode, vanilla still asks Mojang for the UUID of a name typed into /whitelist, and only
 * falls back to the offline UUID (of the lower-cased name) when Mojang has no such player. Offline
 * players log in with the offline UUID of their exact name, so those entries never match.
 *
 * <p>This re-attaches the commands of the existing {@code /whitelist add|remove <targets>} nodes
 * (Brigadier merges nodes of the same name and keeps vanilla's permission check and suggestions).
 * In offline mode a typed name becomes the offline UUID of exactly that name; selectors such as
 * {@code @a} still resolve to the online players. In online mode vanilla behaviour is unchanged.
 */
public class OfflineWhitelist implements DedicatedServerModInitializer {
	private static final String TARGETS = "targets";

	@Override
	public void onInitializeServer() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			if (!environment.dedicated) {
				return;
			}
			dispatcher.register(CommandManager.literal("whitelist")
					.then(CommandManager.literal("add")
							.then(CommandManager.argument(TARGETS, GameProfileArgumentType.gameProfile())
									.executes(context -> WhitelistCommandInvoker.offlinewhitelist$executeAdd(context.getSource(), targets(context)))))
					.then(CommandManager.literal("remove")
							.then(CommandManager.argument(TARGETS, GameProfileArgumentType.gameProfile())
									.executes(context -> WhitelistCommandInvoker.offlinewhitelist$executeRemove(context.getSource(), targets(context))))));
		});
	}

	private static Collection<PlayerConfigEntry> targets(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		String typed = typedTargets(context);
		if (context.getSource().getServer().isOnlineMode() || typed == null || typed.startsWith("@")) {
			return GameProfileArgumentType.getProfileArgument(context, TARGETS);
		}
		if (!StringHelper.isValidPlayerName(typed)) {
			throw GameProfileArgumentType.UNKNOWN_PLAYER_EXCEPTION.create();
		}
		return List.of(PlayerConfigEntry.fromNickname(typed));
	}

	private static String typedTargets(CommandContext<ServerCommandSource> context) {
		for (ParsedCommandNode<ServerCommandSource> node : context.getNodes()) {
			if (TARGETS.equals(node.getNode().getName())) {
				return node.getRange().get(context.getInput());
			}
		}
		return null;
	}
}
