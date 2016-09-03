package me.lucko.luckperms.commands.migration.subcommands;

import de.bananaco.bpermissions.api.*;
import me.lucko.luckperms.LuckPermsPlugin;
import me.lucko.luckperms.api.Logger;
import me.lucko.luckperms.commands.CommandResult;
import me.lucko.luckperms.commands.Predicate;
import me.lucko.luckperms.commands.Sender;
import me.lucko.luckperms.commands.SubCommand;
import me.lucko.luckperms.constants.Constants;
import me.lucko.luckperms.core.PermissionHolder;
import me.lucko.luckperms.data.LogEntry;
import me.lucko.luckperms.exceptions.ObjectAlreadyHasException;
import me.lucko.luckperms.utils.ArgumentChecker;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

import static me.lucko.luckperms.constants.Permission.MIGRATION;

public class MigrationBPermissions extends SubCommand<Object> {
    private static Field uConfigField;
    private static Method getConfigurationSectionMethod = null;
    private static Method getKeysMethod = null;

    static {
        try {
            uConfigField = Class.forName("de.bananaco.bpermissions.imp.YamlWorld").getDeclaredField("uconfig");
            uConfigField.setAccessible(true);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> getUsers(World world) {
        try {
            Object yamlWorldUsers = uConfigField.get(world);
            if (getConfigurationSectionMethod == null) {
                getConfigurationSectionMethod = yamlWorldUsers.getClass().getMethod("getConfigurationSection", String.class);
                getConfigurationSectionMethod.setAccessible(true);
            }

            Object configSection = getConfigurationSectionMethod.invoke(yamlWorldUsers, "users");
            if (configSection == null) {
                return Collections.emptySet();
            }

            if (getKeysMethod == null) {
                getKeysMethod = configSection.getClass().getMethod("getKeys", boolean.class);
                getKeysMethod.setAccessible(true);
            }

            return (Set<String>) getKeysMethod.invoke(configSection, false);
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    public MigrationBPermissions() {
        super("bpermissions", "Migration from bPermissions", "/%s migration bpermissions", MIGRATION, Predicate.alwaysFalse());
    }

    @Override
    public CommandResult execute(LuckPermsPlugin plugin, Sender sender, Object o, List<String> args, String label) {
        final Logger log = plugin.getLog();

        WorldManager worldManager = WorldManager.getInstance();
        if (worldManager == null) {
            log.severe("bPermissions Migration: Error -> bPermissions is not loaded.");
            return CommandResult.STATE_ERROR;
        }

        log.info("bPermissions Migration: Forcing the plugin to load all data. This could take a while.");
        for (World world : worldManager.getAllWorlds()) {
            Set<String> users = getUsers(world);
            if (users == null) {
                log.severe("bPermissions Migration: Couldn't get a list of users.");
                return CommandResult.FAILURE;
            }
            users.forEach(s -> world.loadOne(s, CalculableType.USER));
        }

        // Migrate one world at a time.
        log.info("bPermissions Migration: Starting world migration.");
        for (World world : worldManager.getAllWorlds()) {
            log.info("bPermissions Migration: Migrating world: " + world.getName());
            
            // Migrate all groups
            log.info("bPermissions Migration: Starting group migration in world " + world.getName() + ".");
            int groupCount = 0;
            for (Calculable group : world.getAll(CalculableType.GROUP)) {
                groupCount++;
                String groupName = group.getName().toLowerCase();
                if (group.getName().equalsIgnoreCase(world.getDefaultGroup())) {
                    groupName = "default";
                }

                // Make a LuckPerms group for the one being migrated.
                plugin.getDatastore().createAndLoadGroup(groupName);
                me.lucko.luckperms.groups.Group lpGroup = plugin.getGroupManager().get(groupName);
                try {
                    LogEntry.build()
                            .actor(Constants.getConsoleUUID()).actorName(Constants.getConsoleName())
                            .acted(lpGroup).action("create")
                            .build().submit(plugin);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
                
                migrateHolder(plugin, world, group, lpGroup);
                plugin.getDatastore().saveGroup(lpGroup);
            }
            log.info("bPermissions Migration: Migrated " + groupCount + " groups in world " + world.getName() + ".");

            // Migrate all users
            log.info("bPermissions Migration: Starting user migration in world " + world.getName() + ".");
            int userCount = 0;
            for (Calculable user : world.getAll(CalculableType.USER)) {
                userCount++;

                // There is no mention of UUIDs in the API. I assume that name = uuid. idk?
                UUID uuid;
                try {
                    uuid = UUID.fromString(user.getName());
                } catch (IllegalArgumentException e) {
                    uuid = plugin.getUUID(user.getName());
                }

                if (uuid == null) {
                    log.info("bPermissions Migration: Unable to migrate user " + user.getName() + ". Unable to get UUID.");
                    continue;
                }

                // Make a LuckPerms user for the one being migrated.
                plugin.getDatastore().loadUser(uuid, "null");
                me.lucko.luckperms.users.User lpUser = plugin.getUserManager().get(uuid);

                migrateHolder(plugin, world, user, lpUser);

                plugin.getDatastore().saveUser(lpUser);
                plugin.getUserManager().cleanup(lpUser);
            }

            log.info("bPermissions Migration: Migrated " + userCount + " users in world " + world.getName() + ".");
        }

        log.info("bPermissions Migration: Success! Completed without any errors.");
        return CommandResult.SUCCESS;
    }
    
    private static void migrateHolder(LuckPermsPlugin plugin, World world, Calculable c, PermissionHolder holder) {
        // Migrate the groups permissions in this world
        for (Permission p : c.getPermissions()) {
            try {
                holder.setPermission(p.name(), p.isTrue(), "global", world.getName());
                LogEntry.build()
                        .actor(Constants.getConsoleUUID()).actorName(Constants.getConsoleName())
                        .acted(holder).action("set " + p.name() + " " + p.isTrue() + " global " + world.getName())
                        .build().submit(plugin);
            } catch (Exception ex) {
                if (!(ex instanceof ObjectAlreadyHasException)) {
                    ex.printStackTrace();
                }
            }

            // Include any child permissions
            for (Map.Entry<String, Boolean> child : p.getChildren().entrySet()) {
                try {
                    holder.setPermission(child.getKey(), child.getValue(), "global", world.getName());
                    LogEntry.build()
                            .actor(Constants.getConsoleUUID()).actorName(Constants.getConsoleName())
                            .acted(holder).action("set " + child.getKey() + " " + child.getValue() + " global " + world.getName())
                            .build().submit(plugin);
                } catch (Exception ex) {
                    if (!(ex instanceof ObjectAlreadyHasException)) {
                        ex.printStackTrace();
                    }
                }
            }
        }

        // Migrate any inherited groups
        for (Group parent : c.getGroups()) {
            try {
                holder.setPermission("group." + parent.getName(), true, "global", world.getName());
                LogEntry.build()
                        .actor(Constants.getConsoleUUID()).actorName(Constants.getConsoleName())
                        .acted(holder).action("setinherit " + parent.getName() + " global " + world.getName())
                        .build().submit(plugin);
            } catch (Exception ex) {
                if (!(ex instanceof ObjectAlreadyHasException)) {
                    ex.printStackTrace();
                }
            }
        }

        // Migrate existing meta
        for (Map.Entry<String, String> meta : c.getMeta().entrySet()) {
            if (meta.getKey().equalsIgnoreCase("prefix") || meta.getKey().equalsIgnoreCase("suffix")) {
                String chatMeta = ArgumentChecker.escapeCharacters(meta.getValue());
                try {
                    holder.setPermission(meta.getKey().toLowerCase() + "." + c.getPriority() + "." + chatMeta, true);
                    LogEntry.build()
                            .actor(Constants.getConsoleUUID()).actorName(Constants.getConsoleName())
                            .acted(holder).action("set " + meta.getKey().toLowerCase() + "." + c.getPriority() + "." + chatMeta + " true")
                            .build().submit(plugin);
                } catch (Exception ex) {
                    if (!(ex instanceof ObjectAlreadyHasException)) {
                        ex.printStackTrace();
                    }
                }
                continue;
            }

            try {
                holder.setPermission("meta." + meta.getKey() + "." + meta.getValue(), true, "global", world.getName());
                LogEntry.build()
                        .actor(Constants.getConsoleUUID()).actorName(Constants.getConsoleName())
                        .acted(holder).action("set meta." + meta.getKey() + "." + meta.getValue() + " true global " + world.getName())
                        .build().submit(plugin);
            } catch (Exception ex) {
                if (!(ex instanceof ObjectAlreadyHasException)) {
                    ex.printStackTrace();
                }
            }
        }
    }
}
