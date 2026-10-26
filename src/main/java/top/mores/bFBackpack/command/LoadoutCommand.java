package top.mores.bFBackpack.command;

import org.bukkit.command.*;
import org.bukkit.entity.Player;
import top.mores.bFBackpack.BFBackpack;
import top.mores.bFBackpack.api.*;
import top.mores.bFBackpack.backpack.BackpackType;
import top.mores.bFBackpack.service.LoadoutService;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/** Console-capable administrative probes for all phase-one operations. */
public final class LoadoutCommand implements CommandExecutor, TabCompleter {
    private final BFBackpack plugin;
    public LoadoutCommand(BFBackpack plugin) { this.plugin = plugin; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { help(sender); return true; }
        String group = args[0].toLowerCase(Locale.ROOT);
        if (!Set.of("catalog", "unlock", "loadout", "weapon", "settings").contains(group)) { help(sender); return true; }
        if (!sender.hasPermission("bfbackpack.admin." + group)) { sender.sendMessage("PERMISSION_DENIED: 你没有该管理命令权限"); return true; }
        LoadoutService api = plugin.backend();
        if (api == null) { sender.sendMessage("NOT_READY: 后端仍在初始化"); return true; }
        try {
            if (args.length < 2) { help(sender); return true; }
            String action = args[1].toLowerCase(Locale.ROOT);
            if (group.equals("catalog")) {
                if (action.equals("list") && args.length == 2) api.catalogSnapshot().filter(null, null, Set.of()).forEach(w -> sender.sendMessage(w.id() + " -> " + w.provider() + "/" + w.gunId() + " " + w.tags()));
                else if (action.equals("reload") && args.length == 2) reply(sender, api.reloadCatalog(plugin.getDataFolder().toPath()));
                else help(sender);
                return true;
            }
            if (args.length < 3) { help(sender); return true; }
            UUID player = playerId(args[2]);
            switch (group) {
                case "unlock" -> {
                    if (action.equals("list") && args.length == 3) reply(sender, api.getPlayerUnlocks(player));
                    else if (args.length == 4 && action.equals("grant")) reply(sender, api.grantUnlock(player, args[3]));
                    else if (args.length == 4 && action.equals("revoke")) reply(sender, api.revokeUnlock(player, args[3]));
                    else if (args.length == 4 && action.equals("check")) reply(sender, api.isUnlocked(player, args[3]));
                    else help(sender);
                }
                case "loadout" -> {
                    if (args.length < 4) { help(sender); return true; }
                    BackpackType type = BackpackType.valueOf(args[3].toUpperCase(Locale.ROOT));
                    if (action.equals("show") && args.length == 4) reply(sender, api.getLoadout(player, type));
                    else if (action.equals("validate") && args.length == 4) reply(sender, api.validateLoadout(player, type));
                    else if (action.equals("deployment") && args.length == 4) reply(sender, api.getDeploymentLoadout(player, type));
                    else if (action.equals("set") && args.length == 6) {
                        LoadoutSlot slot = LoadoutSlot.valueOf(args[4].toUpperCase(Locale.ROOT));
                        reply(sender, api.getLoadout(player, type).thenCompose(r -> r.successful()
                                ? api.selectEquipment(player, type, slot, args[5], r.value().revision()) : CompletableFuture.completedFuture(r)));
                    } else if (action.equals("reset") && args.length == 5) {
                        LoadoutSlot slot = LoadoutSlot.valueOf(args[4].toUpperCase(Locale.ROOT));
                        reply(sender, api.getLoadout(player, type).thenCompose(r -> r.successful()
                                ? api.resetSlot(player, type, slot, r.value().revision()) : CompletableFuture.completedFuture(r)));
                    } else help(sender);
                }
                case "weapon" -> {
                    if (action.equals("show") && args.length == 4) reply(sender, api.getWeaponCustomization(player, args[3]));
                    else if (action.equals("attachment") && args.length == 6) reply(sender,
                            api.getWeaponCustomization(player, args[3]).thenCompose(r -> !r.successful() ? CompletableFuture.completedFuture(r)
                                    : args[5].equalsIgnoreCase("remove") ? api.removeAttachment(player, args[3], args[4], r.value().revision())
                                    : api.selectAttachment(player, args[3], args[4], args[5], r.value().revision())));
                    else help(sender);
                }
                case "settings" -> {
                    if (action.equals("show") && args.length == 3) reply(sender, api.getPreferredClass(player));
                    else if (action.equals("class") && args.length == 4) reply(sender, api.setPreferredClass(player, BackpackType.valueOf(args[3].toUpperCase(Locale.ROOT))));
                    else help(sender);
                }
                default -> help(sender);
            }
        } catch (IllegalArgumentException e) { sender.sendMessage("INVALID_INPUT: 玩家需为在线名称或 UUID，兵种/槽位请使用 Tab 补全"); }
        return true;
    }
    private UUID playerId(String raw) {
        Player player = plugin.getServer().getPlayerExact(raw);
        return player == null ? UUID.fromString(raw) : player.getUniqueId();
    }
    private void reply(CommandSender sender, CompletableFuture<? extends LoadoutResult<?>> future) {
        future.whenComplete((r, error) -> {
            if (error != null) plugin.getLogger().log(java.util.logging.Level.SEVERE, "管理命令异步执行失败", error);
            String message = error != null ? "DATABASE_ERROR: 操作未完成，请查看日志" : r.code() + ": " + r.message() + (r.value() == null ? "" : "\n" + r.value());
            if (!plugin.isEnabled()) return;
            try { plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(message)); }
            catch (IllegalStateException e) { plugin.getLogger().fine("命令回复取消：插件正在停用"); }
        });
    }
    private void help(CommandSender s) {
        s.sendMessage(new String[]{"/bfbackpack catalog <list|reload>", "/bfbackpack unlock <grant|revoke|check> <在线玩家或UUID> <itemId>",
                "/bfbackpack unlock list <玩家>", "/bfbackpack loadout <show|validate|deployment> <玩家> <兵种>",
                "/bfbackpack loadout set <玩家> <兵种> <槽位> <itemId>", "/bfbackpack loadout reset <玩家> <兵种> <槽位>",
                "/bfbackpack weapon show <玩家> <weaponId>", "/bfbackpack weapon attachment <玩家> <weaponId> <附件槽位> <attachmentId|remove>",
                "/bfbackpack settings show <玩家>", "/bfbackpack settings class <玩家> <兵种>"});
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0) return List.of();
        List<String> choices = new ArrayList<>();
        if (args.length == 1) choices.addAll(Stream.of("catalog", "unlock", "loadout", "weapon", "settings").filter(g -> sender.hasPermission("bfbackpack.admin." + g)).toList());
        else {
            String group = args[0].toLowerCase(Locale.ROOT);
            if (!sender.hasPermission("bfbackpack.admin." + group)) return List.of();
            if (args.length == 2) choices.addAll(switch (group) {
                case "catalog" -> List.of("list", "reload"); case "unlock" -> List.of("grant", "revoke", "list", "check");
                case "loadout" -> List.of("show", "set", "validate", "reset", "deployment"); case "weapon" -> List.of("show", "attachment");
                case "settings" -> List.of("show", "class"); default -> List.of(); });
            else if (args.length == 3 && !group.equals("catalog")) plugin.getServer().getOnlinePlayers().forEach(p -> choices.add(p.getName()));
            else if (args.length == 4 && (group.equals("loadout") || group.equals("settings"))) Arrays.stream(BackpackType.values()).forEach(t -> choices.add(t.name()));
            else if (args.length == 5 && group.equals("loadout")) Arrays.stream(LoadoutSlot.values()).forEach(s -> choices.add(s.name()));
            LoadoutService backend = plugin.backend();
            if (backend != null) {
                var c = backend.catalogSnapshot();
                if ((args.length == 4 && (group.equals("unlock") || group.equals("weapon"))) || (args.length == 6 && group.equals("loadout"))) choices.addAll(c.weapons().keySet());
                if ((args.length == 4 && group.equals("unlock")) || (args.length == 6 && group.equals("weapon"))) choices.addAll(c.attachments().keySet());
                if (args.length == 5 && group.equals("weapon")) choices.addAll(c.attachmentTypes());
                if (args.length == 6 && group.equals("weapon")) choices.add("remove");
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).distinct().sorted().toList();
    }
}
