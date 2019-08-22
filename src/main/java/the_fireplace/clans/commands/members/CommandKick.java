package the_fireplace.clans.commands.members;

import com.google.common.collect.Lists;
import com.mojang.authlib.GameProfile;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import the_fireplace.clans.cache.ClanCache;
import the_fireplace.clans.commands.ClanSubCommand;
import the_fireplace.clans.model.EnumRank;
import the_fireplace.clans.util.ClanManagementUtil;
import the_fireplace.clans.util.TextStyles;
import the_fireplace.clans.util.translation.TranslationUtil;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public class CommandKick extends ClanSubCommand {
	@Override
	public EnumRank getRequiredClanRank() {
		return EnumRank.ADMIN;
	}

	@Override
	public int getMinArgs() {
		return 1;
	}

	@Override
	public int getMaxArgs() {
		return 1;
	}

	@Override
	public String getUsage(ICommandSender sender) {
		return TranslationUtil.getRawTranslationString(sender, "commands.clan.kick.usage");
	}

	@Override
	public void run(MinecraftServer server, EntityPlayerMP sender, String[] args) throws CommandException {
		GameProfile target = server.getPlayerProfileCache().getGameProfileForUsername(args[0]);

		if(target != null) {
			if(target.getId().equals(sender.getUniqueID())) {
				sender.sendMessage(TranslationUtil.getTranslation(sender.getUniqueID(), "commands.clan.kick.leave").setStyle(TextStyles.RED));
				return;
			}
			if (!ClanCache.getPlayerClans(target.getId()).isEmpty()) {
				if (ClanCache.getPlayerClans(target.getId()).contains(selectedClan)) {
					EnumRank senderRank = selectedClan.getMembers().get(sender.getUniqueID());
					EnumRank targetRank = selectedClan.getMembers().get(target.getId());
					if (senderRank == EnumRank.LEADER || targetRank == EnumRank.MEMBER) {
						ClanManagementUtil.kickMember(server, sender, selectedClan, target);
					} else
						sender.sendMessage(TranslationUtil.getTranslation(sender.getUniqueID(), "commands.clan.kick.authority", target.getName()).setStyle(TextStyles.RED));
				} else
					sender.sendMessage(TranslationUtil.getTranslation(sender.getUniqueID(), "commands.clan.kick.not_in_clan", target.getName(), selectedClan.getClanName()).setStyle(TextStyles.RED));
			} else
				sender.sendMessage(TranslationUtil.getTranslation(sender.getUniqueID(), "commands.clan.common.not_in_clan", target.getName(), selectedClan.getClanName()).setStyle(TextStyles.RED));
		} else
			sender.sendMessage(TranslationUtil.getTranslation(sender.getUniqueID(), "commands.clan.common.playernotfound", args[0]));
	}

	@Override
	public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
		ArrayList<String> playerNames = Lists.newArrayList();
		if(selectedClan != null)
			for(UUID player: selectedClan.getMembers().keySet()) {
				GameProfile playerProf = server.getPlayerProfileCache().getProfileByUUID(player);
				if(playerProf != null && (selectedClan.getMembers().get(player).equals(EnumRank.MEMBER) || (sender instanceof EntityPlayerMP && selectedClan.getMembers().get(((EntityPlayerMP) sender).getUniqueID()).equals(EnumRank.LEADER))))
					playerNames.add(playerProf.getName());
			}
		return args.length == 1 ? playerNames : Collections.emptyList();
	}
}
