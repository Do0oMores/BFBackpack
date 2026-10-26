package top.mores.bFBackpack.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import top.mores.bFBackpack.BFBackpack;
import top.mores.bFBackpack.backpack.Backpack;
import top.mores.bFBackpack.backpack.BackpackService;
import top.mores.bFBackpack.backpack.BackpackSettings;
import top.mores.bFBackpack.backpack.BackpackType;
import top.mores.bFBackpack.backpack.PlayerSession;
import top.mores.bFBackpack.util.Message;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class BackpackCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB_COMMANDS = Arrays.asList("select", "switch", "list", "reload");

    private final BFBackpack plugin;
    private final BackpackService service;

    public BackpackCommand(BFBackpack plugin, BackpackService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            Message.send(sender, "&c该命令只能由玩家执行");
            return true;
        }
        if (!player.hasPermission("bfbackpack.use")) {
            Message.send(player, "&c你没有使用背包的权限");
            return true;
        }

        if (args.length == 0) {
            service.openActive(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "select", "menu" -> service.openSelector(player);
            case "switch" -> handleSwitch(player, args);
            case "list" -> handleList(player);
            case "reload" -> handleReload(player);
            default -> sendHelp(player, label);
        }
        return true;
    }

    private void handleSwitch(Player player, String[] args) {
        if (args.length < 2) {
            Message.send(player, "&7用法：&f/bp switch <" + typeNames() + ">");
            return;
        }
        Optional<BackpackType> type = BackpackType.fromId(args[1]);
        if (type.isEmpty()) {
            Message.send(player, "&c未知的兵种：&f" + args[1] + "&7，可选：&f" + typeNames());
            return;
        }
        service.switchAndOpen(player, type.get());
    }

    private void handleList(Player player) {
        PlayerSession session = service.session(player);
        if (session == null) {
            Message.send(player, "&c背包数据还没准备好，请稍后再试");
            return;
        }
        Message.send(player, "&8&m----&r &f我的背包 &8&m----");
        for (BackpackType type : BackpackType.values()) {
            BackpackSettings settings = plugin.config().backpack(type);
            Backpack backpack = session.backpack(type);
            boolean active = session.active() == type;
            Message.send(player, (active ? "&a▶ " : "&8• ") + "&f" + settings.display() + "背包 "
                    + "&7" + backpack.usedSlots() + "/" + settings.slots()
                    + (active ? " &a（当前）" : ""));
        }
    }

    private void handleReload(Player player) {
        if (!player.hasPermission("bfbackpack.reload")) {
            Message.send(player, "&c你没有重载配置的权限");
            return;
        }
        plugin.reloadPluginConfig();
        Message.send(player, "&a配置已重载：" + plugin.config().describe());
        Message.send(player, "&7数据库连接参数需要重启服务器才能生效");
    }

    private void sendHelp(Player player, String label) {
        Message.send(player, "&8&m----&r &fBFBackpack &8&m----");
        Message.send(player, "&f/" + label + " &7- 打开当前背包");
        Message.send(player, "&f/" + label + " select &7- 打开兵种选择界面");
        Message.send(player, "&f/" + label + " switch <兵种> &7- 切换背包");
        Message.send(player, "&f/" + label + " list &7- 查看各背包使用情况");
        if (player.hasPermission("bfbackpack.reload")) {
            Message.send(player, "&f/" + label + " reload &7- 重载配置");
        }
    }

    private static String typeNames() {
        List<String> names = new ArrayList<>();
        for (BackpackType type : BackpackType.values()) {
            names.add(type.id());
        }
        return String.join("|", names);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> result = new ArrayList<>();
            for (String sub : SUB_COMMANDS) {
                if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    result.add(sub);
                }
            }
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("switch")) {
            List<String> result = new ArrayList<>();
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (BackpackType type : BackpackType.values()) {
                if (type.id().startsWith(prefix)) {
                    result.add(type.id());
                }
                String display = plugin.config().backpack(type).display();
                if (display.startsWith(args[1])) {
                    result.add(display);
                }
            }
            return result;
        }
        return List.of();
    }
}
