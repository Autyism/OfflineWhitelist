package io.github.autyism.offlinewhitelist;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.github.autyism.offlinewhitelist.identity.OfflineUuids;
import io.github.autyism.offlinewhitelist.identity.Person;
import io.github.autyism.offlinewhitelist.identity.Resolver;
import net.minecraft.command.argument.GameProfileArgumentType;
import net.minecraft.command.argument.MessageArgumentType;
import net.minecraft.server.BannedPlayerEntry;
import net.minecraft.server.BannedPlayerList;
import net.minecraft.server.OperatorEntry;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.Whitelist;
import net.minecraft.server.WhitelistEntry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.StringHelper;

/**
 * {@code /whitelist add|remove}, {@code /op}, {@code /deop}, {@code /ban} and {@code /pardon} act on every
 * UUID of a player: their server UUID, premium UUID and the offline UUID of their name (and of former names
 * when removing). A typed name is never looked up the vanilla way, which in offline mode would pick the
 * premium UUID, or the offline UUID of the lower-cased name.
 *
 * <p>The commands are attached to vanilla's existing {@code targets} nodes (Brigadier merges nodes of the same
 * name), so vanilla's permission checks and suggestions stay. Feedback uses vanilla's messages, once per player.
 */
public final class Commands {
	private static final String TARGETS = "targets";
	private static final SimpleCommandExceptionType WHITELIST_ADD_FAILED = new SimpleCommandExceptionType(Text.translatable("commands.whitelist.add.failed"));
	private static final SimpleCommandExceptionType WHITELIST_REMOVE_FAILED = new SimpleCommandExceptionType(Text.translatable("commands.whitelist.remove.failed"));
	private static final SimpleCommandExceptionType OP_FAILED = new SimpleCommandExceptionType(Text.translatable("commands.op.failed"));
	private static final SimpleCommandExceptionType DEOP_FAILED = new SimpleCommandExceptionType(Text.translatable("commands.deop.failed"));
	private static final SimpleCommandExceptionType BAN_FAILED = new SimpleCommandExceptionType(Text.translatable("commands.ban.failed"));
	private static final SimpleCommandExceptionType PARDON_FAILED = new SimpleCommandExceptionType(Text.translatable("commands.pardon.failed"));

	private Commands() {
	}

	static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("whitelist")
				.then(CommandManager.literal("add").then(targets().executes(c -> whitelistAdd(c.getSource(), targets(c)))))
				.then(CommandManager.literal("remove").then(targets().executes(c -> whitelistRemove(c.getSource(), targets(c))))));
		dispatcher.register(CommandManager.literal("op").then(targets().executes(c -> op(c.getSource(), targets(c)))));
		dispatcher.register(CommandManager.literal("deop").then(targets().executes(c -> deop(c.getSource(), targets(c)))));
		dispatcher.register(CommandManager.literal("ban").then(targets().executes(c -> ban(c.getSource(), targets(c), null))
				.then(CommandManager.argument("reason", MessageArgumentType.message())
						.executes(c -> ban(c.getSource(), targets(c), MessageArgumentType.getMessage(c, "reason"))))));
		dispatcher.register(CommandManager.literal("pardon").then(targets().executes(c -> pardon(c.getSource(), targets(c)))));
		dispatcher.register(CommandManager.literal("offlinewhitelist")
				.requires(CommandManager.requirePermissionLevel(CommandManager.ADMINS_CHECK))
				.then(CommandManager.literal("info").then(targets().executes(c -> info(c.getSource(), targets(c))))));
	}

	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, GameProfileArgumentType.GameProfileArgument> targets() {
		return CommandManager.argument(TARGETS, GameProfileArgumentType.gameProfile());
	}

	private static List<Resolver.Target> targets(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		Resolver resolver = OfflineWhitelist.resolver();
		String typed = typedTargets(context);
		if (typed == null || typed.startsWith("@")) {
			List<Resolver.Target> targets = new ArrayList<>();
			for (PlayerConfigEntry entry : GameProfileArgumentType.getProfileArgument(context, TARGETS)) {
				targets.add(resolver.target(entry.id(), entry.name()));
			}
			return targets;
		}
		if (!StringHelper.isValidPlayerName(typed)) {
			throw GameProfileArgumentType.UNKNOWN_PLAYER_EXCEPTION.create();
		}
		return List.of(resolver.target(typed));
	}

	private static String typedTargets(CommandContext<ServerCommandSource> context) {
		for (ParsedCommandNode<ServerCommandSource> node : context.getNodes()) {
			if (TARGETS.equals(node.getNode().getName())) {
				return node.getRange().get(context.getInput());
			}
		}
		return null;
	}

	private static PlayerConfigEntry entry(UUID uuid, Resolver.Target target) {
		return new PlayerConfigEntry(uuid, target.name());
	}

	private static int whitelistAdd(ServerCommandSource source, List<Resolver.Target> targets) throws CommandSyntaxException {
		Whitelist whitelist = source.getServer().getPlayerManager().getWhitelist();
		int changed = 0;
		for (Resolver.Target target : targets) {
			List<UUID> added = new ArrayList<>();
			for (UUID uuid : target.add()) {
				if (!whitelist.isAllowed(entry(uuid, target))) {
					whitelist.add(new WhitelistEntry(entry(uuid, target)));
					added.add(uuid);
				}
			}
			if (!added.isEmpty()) {
				changed++;
				log("whitelist add", target, added);
				source.sendFeedback(() -> Text.translatable("commands.whitelist.add.success", Text.literal(target.name())), true);
			}
		}
		if (changed == 0) {
			throw WHITELIST_ADD_FAILED.create();
		}
		return changed;
	}

	private static int whitelistRemove(ServerCommandSource source, List<Resolver.Target> targets) throws CommandSyntaxException {
		Whitelist whitelist = source.getServer().getPlayerManager().getWhitelist();
		int changed = 0;
		for (Resolver.Target target : targets) {
			List<UUID> removed = new ArrayList<>();
			for (UUID uuid : target.remove()) {
				if (whitelist.isAllowed(entry(uuid, target))) {
					whitelist.remove(entry(uuid, target));
					removed.add(uuid);
				}
			}
			if (!removed.isEmpty()) {
				changed++;
				log("whitelist remove", target, removed);
				source.sendFeedback(() -> Text.translatable("commands.whitelist.remove.success", Text.literal(target.name())), true);
			}
		}
		if (changed == 0) {
			throw WHITELIST_REMOVE_FAILED.create();
		}
		source.getServer().kickNonWhitelistedPlayers();
		return changed;
	}

	private static int op(ServerCommandSource source, List<Resolver.Target> targets) throws CommandSyntaxException {
		PlayerManager players = source.getServer().getPlayerManager();
		int changed = 0;
		for (Resolver.Target target : targets) {
			List<UUID> added = new ArrayList<>();
			for (UUID uuid : target.add()) {
				if (players.getOpList().get(entry(uuid, target)) == null) {
					players.addToOperators(entry(uuid, target));
					added.add(uuid);
				}
			}
			if (!added.isEmpty()) {
				changed++;
				log("op", target, added);
				source.sendFeedback(() -> Text.translatable("commands.op.success", target.name()), true);
			}
		}
		if (changed == 0) {
			throw OP_FAILED.create();
		}
		return changed;
	}

	private static int deop(ServerCommandSource source, List<Resolver.Target> targets) throws CommandSyntaxException {
		PlayerManager players = source.getServer().getPlayerManager();
		int changed = 0;
		for (Resolver.Target target : targets) {
			List<UUID> removed = new ArrayList<>();
			for (UUID uuid : target.remove()) {
				if (players.getOpList().get(entry(uuid, target)) != null) {
					players.removeFromOperators(entry(uuid, target));
					removed.add(uuid);
				}
			}
			if (!removed.isEmpty()) {
				changed++;
				log("deop", target, removed);
				source.sendFeedback(() -> Text.translatable("commands.deop.success", target.name()), true);
			}
		}
		if (changed == 0) {
			throw DEOP_FAILED.create();
		}
		source.getServer().kickNonWhitelistedPlayers();
		return changed;
	}

	private static int ban(ServerCommandSource source, List<Resolver.Target> targets, Text reason) throws CommandSyntaxException {
		PlayerManager players = source.getServer().getPlayerManager();
		BannedPlayerList bans = players.getUserBanList();
		int changed = 0;
		for (Resolver.Target target : targets) {
			List<UUID> added = new ArrayList<>();
			BannedPlayerEntry first = null;
			for (UUID uuid : target.remove()) {
				if (!bans.contains(entry(uuid, target))) {
					BannedPlayerEntry ban = new BannedPlayerEntry(entry(uuid, target), null, source.getName(), null, reason == null ? null : reason.getString());
					bans.add(ban);
					added.add(uuid);
					first = first == null ? ban : first;
				}
			}
			if (!added.isEmpty()) {
				changed++;
				log("ban", target, added);
				BannedPlayerEntry shown = first;
				source.sendFeedback(() -> Text.translatable("commands.ban.success", Text.literal(target.name()), shown.getReasonText()), true);
				for (UUID uuid : target.remove()) {
					ServerPlayerEntity player = players.getPlayer(uuid);
					if (player != null) {
						player.networkHandler.disconnect(Text.translatable("multiplayer.disconnect.banned"));
					}
				}
			}
		}
		if (changed == 0) {
			throw BAN_FAILED.create();
		}
		return changed;
	}

	private static int pardon(ServerCommandSource source, List<Resolver.Target> targets) throws CommandSyntaxException {
		BannedPlayerList bans = source.getServer().getPlayerManager().getUserBanList();
		int changed = 0;
		for (Resolver.Target target : targets) {
			List<UUID> removed = new ArrayList<>();
			for (UUID uuid : target.remove()) {
				if (bans.contains(entry(uuid, target))) {
					bans.remove(entry(uuid, target));
					removed.add(uuid);
				}
			}
			if (!removed.isEmpty()) {
				changed++;
				log("pardon", target, removed);
				source.sendFeedback(() -> Text.translatable("commands.pardon.success", Text.literal(target.name())), true);
			}
		}
		if (changed == 0) {
			throw PARDON_FAILED.create();
		}
		return changed;
	}

	/** {@code /offlinewhitelist info <player>}: the player's UUIDs and which lists they are on. */
	private static int info(ServerCommandSource source, List<Resolver.Target> targets) {
		PlayerManager players = source.getServer().getPlayerManager();
		for (Resolver.Target target : targets) {
			Person person = target.person();
			List<Text> lines = new ArrayList<>();
			if (person != null) {
				lines.add(Text.literal(person.name()).formatted(Formatting.GOLD)
						.append(Text.literal(person.formerNames().isEmpty() ? "" : " (formerly " + String.join(", ", person.formerNames()) + ")").formatted(Formatting.GRAY)));
				lines.add(uuidLine("server UUID", person.uuid()));
				lines.add(uuidLine("premium UUID", person.premium()));
				lines.add(uuidLine("offline UUID of the name", OfflineUuids.of(person.name())));
			} else {
				lines.add(Text.literal(target.name()).formatted(Formatting.GOLD)
						.append(Text.literal(" has not joined with a premium account yet").formatted(Formatting.GRAY)));
				for (UUID uuid : target.add()) {
					lines.add(uuidLine(uuid.version() == 4 ? "premium UUID" : "offline UUID", uuid));
				}
			}
			String whitelisted = target.remove().stream().filter(uuid -> players.getWhitelist().isAllowed(entry(uuid, target)))
					.map(Commands::shortUuid).collect(Collectors.joining(", "));
			String op = target.remove().stream().map(uuid -> players.getOpList().get(entry(uuid, target))).filter(e -> e != null)
					.map(e -> "level " + e.getLevel().getLevel().getLevel()).distinct().collect(Collectors.joining(", "));
			String banned = target.remove().stream().filter(uuid -> players.getUserBanList().contains(entry(uuid, target)))
					.map(Commands::shortUuid).collect(Collectors.joining(", "));
			lines.add(Text.literal("whitelist: " + (whitelisted.isEmpty() ? "no" : whitelisted)
					+ " · op: " + (op.isEmpty() ? "no" : op) + " · banned: " + (banned.isEmpty() ? "no" : banned)).formatted(Formatting.GRAY));
			for (Text line : lines) {
				source.sendFeedback(() -> line, false);
			}
		}
		return targets.size();
	}

	private static Text uuidLine(String label, UUID uuid) {
		return Text.literal("  " + label + ": ").formatted(Formatting.GRAY).append(Text.literal(uuid.toString()).formatted(Formatting.WHITE));
	}

	private static String shortUuid(UUID uuid) {
		return uuid.toString().substring(0, 8);
	}

	private static void log(String what, Resolver.Target target, List<UUID> uuids) {
		OfflineWhitelist.LOGGER.info("[Offline Whitelist] {} {}: {}", what, target.name(), uuids);
	}
}
