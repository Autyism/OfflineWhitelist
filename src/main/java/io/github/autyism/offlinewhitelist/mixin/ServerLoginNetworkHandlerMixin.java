package io.github.autyism.offlinewhitelist.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.authlib.GameProfile;
import io.github.autyism.offlinewhitelist.ListSync;
import io.github.autyism.offlinewhitelist.LoginFlow;
import net.minecraft.network.ClientConnection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLoginNetworkHandler.class)
public abstract class ServerLoginNetworkHandlerMixin {
	@Shadow
	@Final
	MinecraftServer server;

	@Shadow
	@Final
	ClientConnection connection;

	@Shadow
	abstract void startVerify(GameProfile profile);

	@Shadow
	public abstract void disconnect(Text reason);

	/**
	 * Offline mode: instead of the offline profile right away, the profile with the player's server UUID and
	 * skin, worked out on a worker thread. Until then the handler waits in the hello state, ticking towards
	 * vanilla's 30-second login timeout.
	 */
	@WrapOperation(method = "onHello", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/network/ServerLoginNetworkHandler;startVerify(Lcom/mojang/authlib/GameProfile;)V", ordinal = 1))
	private void offlinewhitelist$resolveIdentity(ServerLoginNetworkHandler handler, GameProfile offlineProfile, Operation<Void> original) {
		if (this.connection.isLocal()) {
			original.call(handler, offlineProfile);
			return;
		}
		LoginFlow.start(this.server, offlineProfile.name(), profile -> this.startVerify(profile), reason -> this.disconnect(reason));
	}

	/** Online mode: an authenticated premium player gets the server UUID their account has here. */
	@ModifyVariable(method = "startVerify", at = @At("HEAD"), argsOnly = true)
	private GameProfile offlinewhitelist$remapPremium(GameProfile profile) {
		return this.server.isOnlineMode() ? LoginFlow.remapOnline(this.server, profile) : profile;
	}

	/** Runs on the server thread right before the whitelist, ban and player-limit checks. */
	@Inject(method = "tickVerify", at = @At("HEAD"))
	private void offlinewhitelist$syncLists(GameProfile profile, CallbackInfo ci) {
		ListSync.sync(this.server, profile.id());
	}
}
