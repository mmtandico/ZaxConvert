package com.zaxconvert.command;

import com.zaxconvert.ZaxConvert;
import com.zaxconvert.conversion.ResourcePackConverter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;

/**
 * Main command executor for ZaxConvert
 * Handles /zc and /zinv commands
 */
public class ZaxConvertCommand implements CommandExecutor {

    private final ZaxConvert plugin;
    private final ResourcePackConverter converter;
    private final FileConfiguration config;

    public ZaxConvertCommand(ZaxConvert plugin) {
        this.plugin = plugin;
        this.converter = new ResourcePackConverter(plugin);
        this.config = plugin.getConfig();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("zaxconvert.use")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use ZaxConvert commands.");
            return true;
        }

        if (args.length == 0) {
            return handleAutoConvert(sender);
        }

        String subcommand = args[0].toLowerCase();

        switch (subcommand) {
            case "convert":
                return handleConvert(sender, args);
            case "reload":
                return handleReload(sender, args);
            case "help":
                showHelp(sender);
                return true;
            case "info":
                return handleInfo(sender, args);
            case "setup":
                return handleSetup(sender, args);
            default:
                sender.sendMessage(ChatColor.RED + "Unknown subcommand: " + subcommand);
                sender.sendMessage(ChatColor.YELLOW + "Use /zc help for available commands.");
                return true;
        }
    }

    /** /zc: scan all providers (Nexo, ItemsAdder, Oraxen) and convert for Geyser. */
    private boolean handleAutoConvert(CommandSender sender) {
        if (!sender.hasPermission("zaxconvert.convert")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to convert resource packs.");
            return true;
        }
        sender.sendMessage(ChatColor.GOLD + "Scanning providers and converting packs...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                report(sender, converter.convertAllProviders()));
        return true;
    }

    private boolean handleConvert(CommandSender sender, String[] args) {
        if (!sender.hasPermission("zaxconvert.convert")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to convert resource packs.");
            return true;
        }

        // /zc convert <resourcepack>
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /zc convert <resourcepack>");
            sender.sendMessage(ChatColor.YELLOW + "  <resourcepack>: Path to Java resource pack (zip or folder)");
            sender.sendMessage(ChatColor.YELLOW + "  Or just run /zc to auto-convert all providers.");
            return true;
        }

        File pack = new File(args[1]);
        if (!pack.exists()) {
            sender.sendMessage(ChatColor.RED + "Pack not found: " + args[1]);
            return true;
        }
        String name = pack.getName().replaceAll("\\.zip$", "");
        sender.sendMessage(ChatColor.GOLD + "Converting " + pack.getName() + "...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                report(sender, java.util.Collections.singletonList(converter.convertPack(name, pack))));
        return true;
    }

    private void report(CommandSender sender, java.util.List<ResourcePackConverter.Result> results) {
        boolean any = false;
        for (ResourcePackConverter.Result r : results) {
            ChatColor c = r.success ? ChatColor.GREEN : ChatColor.YELLOW;
            sender.sendMessage(c + "[" + r.provider + "] " + r.message);
            any |= r.success;
        }
        if (any) {
            sender.sendMessage(ChatColor.GREEN + "Done! Restart the server (or Geyser) so Bedrock players get the new pack.");
        } else {
            sender.sendMessage(ChatColor.RED + "Nothing was converted.");
        }
    }

    private boolean handleReload(CommandSender sender, String[] args) {
        if (!sender.hasPermission("zaxconvert.reload")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to reload ZaxConvert.");
            return true;
        }

        plugin.reloadConfig();
        converter.reload();
        sender.sendMessage(ChatColor.GREEN + "ZaxConvert configuration reloaded!");
        return true;
    }

    private boolean handleInfo(CommandSender sender, String[] args) {
        sender.sendMessage(ChatColor.GOLD + "=== ZaxConvert Information ===");
        sender.sendMessage(ChatColor.YELLOW + "Version: " + plugin.getDescription().getVersion());
        sender.sendMessage(ChatColor.YELLOW + "Author: " + plugin.getDescription().getAuthors().get(0));
        sender.sendMessage(ChatColor.YELLOW + "Description: " + plugin.getDescription().getDescription());
        sender.sendMessage(ChatColor.YELLOW + "");
        sender.sendMessage(ChatColor.YELLOW + "Supported Plugins:");
        sender.sendMessage(ChatColor.YELLOW + "  Nexo: " + (config.getBoolean("plugin-support.nexo-enabled") ? ChatColor.GREEN + "Enabled" : ChatColor.RED + "Disabled"));
        sender.sendMessage(ChatColor.YELLOW + "  ItemsAdder: " + (config.getBoolean("plugin-support.itemsadder-enabled") ? ChatColor.GREEN + "Enabled" : ChatColor.RED + "Disabled"));
        sender.sendMessage(ChatColor.YELLOW + "  Oraxen: " + (config.getBoolean("plugin-support.oraxen-enabled") ? ChatColor.GREEN + "Enabled" : ChatColor.RED + "Disabled"));
        sender.sendMessage(ChatColor.YELLOW + "");
        sender.sendMessage(ChatColor.YELLOW + "Geyser Integration:");
        sender.sendMessage(ChatColor.YELLOW + "  Auto-place files: " + (config.getBoolean("geyser.auto-place-files") ? ChatColor.GREEN + "Yes" : ChatColor.RED + "No"));
        return true;
    }

    private boolean handleSetup(CommandSender sender, String[] args) {
        if (!sender.hasPermission("zaxconvert.setup")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to run setup.");
            return true;
        }

        sender.sendMessage(ChatColor.GOLD + "Running ZaxConvert setup...");
        sender.sendMessage(ChatColor.YELLOW + "This will create necessary directories and configuration files.");

        // Create necessary directories
        File resourcePackDir = new File(plugin.getDataFolder(), "resourcepacks");
        if (!resourcePackDir.exists()) {
            resourcePackDir.mkdirs();
            sender.sendMessage(ChatColor.GREEN + "Created resource packs directory: " + resourcePackDir.getAbsolutePath());
        }

        sender.sendMessage(ChatColor.GREEN + "Setup completed!");
        return true;
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== ZaxConvert Commands ===");
        sender.sendMessage(ChatColor.YELLOW + "/zc convert <resourcepack> [outputdir]");
        sender.sendMessage(ChatColor.YELLOW + "  Convert a Java resource pack to Bedrock format");
        sender.sendMessage(ChatColor.YELLOW + "");
        sender.sendMessage(ChatColor.YELLOW + "/zc reload");
        sender.sendMessage(ChatColor.YELLOW + "  Reload plugin configuration");
        sender.sendMessage(ChatColor.YELLOW + "");
        sender.sendMessage(ChatColor.YELLOW + "/zc info");
        sender.sendMessage(ChatColor.YELLOW + "  Show plugin information");
        sender.sendMessage(ChatColor.YELLOW + "");
        sender.sendMessage(ChatColor.YELLOW + "/zc setup");
        sender.sendMessage(ChatColor.YELLOW + "  Run initial setup");
        sender.sendMessage(ChatColor.YELLOW + "");
        sender.sendMessage(ChatColor.YELLOW + "/zc help");
        sender.sendMessage(ChatColor.YELLOW + "  Show this help message");
        sender.sendMessage(ChatColor.YELLOW + "");
        sender.sendMessage(ChatColor.YELLOW + "Aliases:");
        sender.sendMessage(ChatColor.YELLOW + "  /zinv - Same as /zc (for inventory management)");
        sender.sendMessage(ChatColor.YELLOW + "");
        sender.sendMessage(ChatColor.YELLOW + "Examples:");
        sender.sendMessage(ChatColor.YELLOW + "  /zc convert plugins/ZaxConvert/resourcepacks/mypack.zip");
        sender.sendMessage(ChatColor.YELLOW + "  /zc convert ./myresourcepack ./Geyster/packs");
    }

    private void showPluginSupportStatus(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Plugin Support Status:");
        if (config.getBoolean("plugin-support.nexo-enabled")) {
            sender.sendMessage(ChatColor.YELLOW + "  Nexo: " + ChatColor.GREEN + "Enabled");
        } else {
            sender.sendMessage(ChatColor.YELLOW + "  Nexo: " + ChatColor.RED + "Disabled");
        }

        if (config.getBoolean("plugin-support.itemsadder-enabled")) {
            sender.sendMessage(ChatColor.YELLOW + "  ItemsAdder: " + ChatColor.GREEN + "Enabled");
        } else {
            sender.sendMessage(ChatColor.YELLOW + "  ItemsAdder: " + ChatColor.RED + "Disabled");
        }

        if (config.getBoolean("plugin-support.oraxen-enabled")) {
            sender.sendMessage(ChatColor.YELLOW + "  Oraxen: " + ChatColor.GREEN + "Enabled");
        } else {
            sender.sendMessage(ChatColor.YELLOW + "  Oraxen: " + ChatColor.RED + "Disabled");
        }
    }

    private String getDefaultGeyserOutputDir() {
        // Try to detect Geyser installation
        File geyserDir = new File("../Geyser");
        if (geyserDir.exists()) {
            File packsDir = new File(geyserDir, "packs");
            if (packsDir.exists()) {
                return packsDir.getAbsolutePath();
            }
        }

        // Fallback to relative path
        return "../Geyser/packs";
    }
}