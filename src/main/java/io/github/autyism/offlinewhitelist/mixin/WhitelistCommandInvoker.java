package io.github.autyism.offlinewhitelist.mixin;

import java.util.Collection;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.dedicated.command.WhitelistCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Gives access to vanilla's add/remove logic, so feedback messages and kicking stay vanilla. */
@Mixin(WhitelistCommand.class)
public interface WhitelistCommandInvoker {
	@Invoker("executeAdd")
	static int offlinewhitelist$executeAdd(ServerCommandSource source, Collection<PlayerConfigEntry> targets) throws CommandSyntaxException {
		throw new AssertionError();
	}

	@Invoker("executeRemove")
	static int offlinewhitelist$executeRemove(ServerCommandSource source, Collection<PlayerConfigEntry> targets) throws CommandSyntaxException {
		throw new AssertionError();
	}
}
