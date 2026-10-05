package com.zaxconvert;

import com.zaxconvert.command.ZaxConvertCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ZaxConvert extends JavaPlugin implements CommandExecutor, TabCompleter {

    private FileConfiguration config;
    private ZaxConvertCommand commandExecutor;

    @Override
    public void onEnable() {
        // Save default config
        saveDefaultConfig();
        this.config = getConfig();

        // Initialize command executor
        this.commandExecutor = new ZaxConvertCommand(this);

        // Register command executors
        getCommand("zc").setExecutor(commandExecutor);
        getCommand("zc").setTabCompleter(this);
        getCommand("zinv").setExecutor(commandExecutor);
        getCommand("zinv").setTabCompleter(this);

        getLogger().info("ZaxConvert has been enabled!");
        getLogger().info("Supports Nexo, ItemsAdder, and Oraxen plugin conversions");
        getLogger().info("Version: " + getDescription().getVersion());
    }

    @Override
    public void onDisable() {
        getLogger().info("ZaxConvert has been disabled!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Delegate to command executor
        return commandExecutor.onCommand(sender, command, label, args);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (command.getName().equalsIgnoreCase("zc")) {
            if (args.length == 1) {
                completions.addAll(Arrays.asList("convert", "reload", "help", "info", "setup"));
            } else if (args.length == 2 && args[0].equalsIgnoreCase("convert")) {
                // Could add file/path completions here
                completions.add("<resourcepack>");
            } else if (args.length == 3 && args[0].equalsIgnoreCase("convert")) {
                completions.add("[outputdir]");
            }
        } else if (command.getName().equalsIgnoreCase("zinv")) {
            if (args.length == 0) {
                completions.add(""); // Empty arg for opening inventory
            }
        }

        // Filter completions based on current input
        if (args.length > 0) {
            String currentArg = args[args.length - 1].toLowerCase();
            completions.removeIf(s -> !s.toLowerCase().startsWith(currentArg));
        }

        return completions;
    }

    /**
     * Gets the plugin configuration
     * @return The plugin's FileConfiguration
     */
    public FileConfiguration getPluginConfig() {
        return config;
    }

    /**
     * Reloads the plugin configuration
     */
    public void reloadPluginConfig() {
        reloadConfig();
        this.config = getConfig();
    }
}