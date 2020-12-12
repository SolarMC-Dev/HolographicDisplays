/*
 * Copyright (C) filoghost and contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.filoghost.holographicdisplays;

import me.filoghost.fcommons.BaseJavaPlugin;
import me.filoghost.fcommons.logging.Log;
import me.filoghost.fcommons.reflection.ReflectUtils;
import me.filoghost.holographicdisplays.api.internal.BackendAPI;
import me.filoghost.holographicdisplays.bridge.bungeecord.BungeeServerTracker;
import me.filoghost.holographicdisplays.bridge.protocollib.ProtocolLibHook;
import me.filoghost.holographicdisplays.bridge.protocollib.current.ProtocolLibHookImpl;
import me.filoghost.holographicdisplays.commands.main.HologramsCommandHandler;
import me.filoghost.holographicdisplays.common.NMSVersion;
import me.filoghost.holographicdisplays.common.VersionUtils;
import me.filoghost.holographicdisplays.disk.Configuration;
import me.filoghost.holographicdisplays.disk.HologramDatabase;
import me.filoghost.holographicdisplays.disk.UnicodeSymbols;
import me.filoghost.holographicdisplays.listener.MainListener;
import me.filoghost.holographicdisplays.nms.interfaces.NMSManager;
import me.filoghost.holographicdisplays.object.DefaultBackendAPI;
import me.filoghost.holographicdisplays.object.NamedHologram;
import me.filoghost.holographicdisplays.object.NamedHologramManager;
import me.filoghost.holographicdisplays.object.PluginHologram;
import me.filoghost.holographicdisplays.object.PluginHologramManager;
import me.filoghost.holographicdisplays.placeholder.AnimationsRegister;
import me.filoghost.holographicdisplays.placeholder.PlaceholdersManager;
import me.filoghost.holographicdisplays.task.BungeeCleanupTask;
import me.filoghost.holographicdisplays.task.StartupLoadHologramsTask;
import me.filoghost.holographicdisplays.task.WorldPlayerCounterTask;
import me.filoghost.updatechecker.UpdateChecker;
import org.bstats.bukkit.MetricsLite;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HolographicDisplays extends BaseJavaPlugin {
    
    // The main instance of the plugin.
    private static HolographicDisplays instance;
    
    // The manager for net.minecraft.server access.
    private static NMSManager nmsManager;
    
    // The listener for all the Bukkit and NMS events.
    private static MainListener mainListener;
    
    // The command handler, just in case a plugin wants to register more commands.
    private static HologramsCommandHandler commandHandler;
    
    // The new version found by the updater, null if there is no new version.
    private static String newVersion;
    
    // Not null if ProtocolLib is installed and successfully loaded.
    private static ProtocolLibHook protocolLibHook;
    
    @Override
    public void onCheckedEnable() throws PluginEnableException {
        // Warn about plugin reloaders and the /reload command.
        if (instance != null || System.getProperty("HolographicDisplaysLoaded") != null) {
            Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "[HolographicDisplays] Please do not use /reload or plugin reloaders. Use the command \"/holograms reload\" instead. You will receive no support for doing this operation.");
        }
        
        System.setProperty("HolographicDisplaysLoaded", "true");
        instance = this;
        
        // Load placeholders.yml.
        UnicodeSymbols.load(this);

        // Load the configuration.
        Configuration.load(this);
        
        if (Configuration.updateNotification) {
            UpdateChecker.run(this, 75097, (String newVersion) -> {
                HolographicDisplays.newVersion = newVersion;
                Log.info("Found a new version available: " + newVersion);
                Log.info("Download it on Bukkit Dev:");
                Log.info("dev.bukkit.org/projects/holographic-displays");
            });
        }
        
        // The bungee chat API is required.
        if (!ReflectUtils.isClassLoaded("net.md_5.bungee.api.chat.ComponentBuilder")) {
            throw new PluginEnableException(
                    "Holographic Displays requires the new chat API.",
                    "You are probably running CraftBukkit instead of Spigot.");
        }
        
        if (!NMSVersion.isValid()) {
            throw new PluginEnableException(
                "Holographic Displays does not support this server version.",
                "Supported Spigot versions: from 1.8.3 to 1.16.4.");
        }
        
        try {
            nmsManager = (NMSManager) Class.forName("me.filoghost.holographicdisplays.nms." + NMSVersion.getCurrent() + ".NmsManagerImpl").getConstructor().newInstance();
        } catch (Throwable t) {
            throw new PluginEnableException(t, "Couldn't initialize the NMS manager.");
        }

        try {
            nmsManager.setup();
        } catch (Exception e) {
            throw new PluginEnableException(e, "Holographic Displays was unable to register custom entities.");
        }
        
        // ProtocolLib check.
        hookProtocolLib();
        
        // Load animation files and the placeholder manager.
        PlaceholdersManager.load(this);
        try {
            AnimationsRegister.loadAnimations(this);
        } catch (Exception ex) {
            Log.warning("Failed to load animation files!", ex);
        }
        
        // Initialize other static classes.
        HologramDatabase.loadYamlFile(this);
        BungeeServerTracker.startTask(Configuration.bungeeRefreshSeconds);
        
        // Start repeating tasks.
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new BungeeCleanupTask(), 5 * 60 * 20, 5 * 60 * 20);
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new WorldPlayerCounterTask(), 0L, 3 * 20);
        
        if (getCommand("holograms") == null) {
            throw new PluginEnableException(
                "Holographic Displays was unable to register the command \"holograms\".",
                "This can be caused by edits to plugin.yml or other plugins.");
        }
        
        getCommand("holograms").setExecutor(commandHandler = new HologramsCommandHandler());
        Bukkit.getPluginManager().registerEvents(mainListener = new MainListener(nmsManager), this);

        // Register bStats metrics
        int pluginID = 3123;
        new MetricsLite(this, pluginID);
        
        // Holograms are loaded later, when the worlds are ready.
        Bukkit.getScheduler().runTask(this, new StartupLoadHologramsTask());
        
        // Enable the API.
        BackendAPI.setImplementation(new DefaultBackendAPI());
    }
    

    @Override
    public void onDisable() {
        for (NamedHologram hologram : NamedHologramManager.getHolograms()) {
            hologram.despawnEntities();
        }
        for (PluginHologram hologram : PluginHologramManager.getHolograms()) {
            hologram.despawnEntities();
        }
    }
    
    public static NMSManager getNMSManager() {
        return nmsManager;
    }
    
    public static MainListener getMainListener() {
        return mainListener;
    }

    public static HologramsCommandHandler getCommandHandler() {
        return commandHandler;
    }

    public static HolographicDisplays getInstance() {
        return instance;
    }


    public static String getNewVersion() {
        return newVersion;
    }
    
    
    public void hookProtocolLib() {
        if (!Bukkit.getPluginManager().isPluginEnabled("ProtocolLib")) {
            return;
        }

        try {
            String protocolVersion = Bukkit.getPluginManager().getPlugin("ProtocolLib").getDescription().getVersion();
            Matcher versionNumbersMatcher = Pattern.compile("([0-9\\.])+").matcher(protocolVersion);
            
            if (!versionNumbersMatcher.find()) {
                throw new IllegalArgumentException("could not find version numbers pattern");
            }
            
            String versionNumbers = versionNumbersMatcher.group();
            
            if (!VersionUtils.isVersionGreaterEqual(versionNumbers, "4.1")) {
                Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "[Holographic Displays] Detected old version of ProtocolLib, support disabled. You must use ProtocolLib 4.1 or higher.");
                return;
            }
            
        } catch (Exception e) {
            Log.warning("Could not detect ProtocolLib version (" + e.getClass().getName() + ": " + e.getMessage() + "), enabling support anyway and hoping for the best. If you get errors, please contact the author.");
        }
        
        try {
            ProtocolLibHook protocolLibHook = new ProtocolLibHookImpl();
            
            if (protocolLibHook.hook(this, nmsManager)) {
                HolographicDisplays.protocolLibHook = protocolLibHook;
                Log.info("Enabled player relative placeholders with ProtocolLib.");
            }
        } catch (Exception e) {
            Log.warning("Failed to load ProtocolLib support. Is it updated?", e);
        }
    }
    
    
    public static boolean hasProtocolLibHook() {
        return protocolLibHook != null;
    }
    
    
    public static ProtocolLibHook getProtocolLibHook() {
        return protocolLibHook;
    }
    
    
    public static boolean isConfigFile(File file) {
        return file.getName().toLowerCase().endsWith(".yml");
    }
    
}
