package me.realized.duels.dueling;

import me.realized.duels.Core;
import me.realized.duels.arena.Arena;
import me.realized.duels.arena.ArenaManager;
import me.realized.duels.configuration.Config;
import me.realized.duels.data.DataManager;
import me.realized.duels.data.MatchData;
import me.realized.duels.data.UserData;
import me.realized.duels.hooks.EssentialsHook;
import me.realized.duels.hooks.HookManager;
import me.realized.duels.kits.Kit;
import me.realized.duels.kits.KitManager;
import me.realized.duels.utilities.PlayerUtil;
import me.realized.duels.utilities.StringUtil;
import me.realized.duels.utilities.inventory.Metadata;
import org.bukkit.*;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Calendar;
import java.util.GregorianCalendar;

public class DuelManager implements Listener {

    private final Core instance;
    private final Config config;
    private final EssentialsHook essentialsHook;
    private final DataManager dataManager;
    private final KitManager kitManager;
    private final ArenaManager arenaManager;

    public DuelManager(Core instance) {
        this.instance = instance;
        this.config = instance.getConfiguration();
        this.essentialsHook = (EssentialsHook) instance.getHookManager().get("Essentials");
        this.dataManager = instance.getDataManager();
        this.kitManager = instance.getKitManager();
        this.arenaManager = instance.getArenaManager();
        Bukkit.getPluginManager().registerEvents(this, instance);
    }

    public void startMatch(Player player, Player target, Request request) {
        Arena arena;

        if (config.getBoolean("allow-arena-selecting")) {
            arena = arenaManager.getArena(request.getArena());

            if (arena == null || arena.isUsed()) {
                PlayerUtil.pm("&cThe selected arena is no longer available, please choose a different arena.", player, target);
                return;
            }
        } else {
            arena = arenaManager.getAvailableArena();

            if (arena == null) {
                PlayerUtil.pm("&cNo available arenas were found, please try again later.", player, target);
                return;
            }
        }

        Location pos1 = arena.getPositions().get(1);
        Location pos2 = arena.getPositions().get(2);
        PlayerUtil.refreshChunk(pos1, pos2);

        if (!PlayerUtil.canTeleportTo(player, pos1) || !PlayerUtil.canTeleportTo(target, pos2)) {
            PlayerUtil.pm("&cFailed to teleport to the arena! Please contact an administrator.", player, target);
            return;
        }

        Kit contents = kitManager.getKit(request.getKit());
        String arenaName = request.getArena() != null ? request.getArena() : "random";

        arena.setUsed(true);
        arena.getCurrentMatch().setData(player, target);
        player.closeInventory();
        target.closeInventory();
        player.teleport(pos1);
        target.teleport(pos2);
        essentialsHook.setUnvanished(player);
        essentialsHook.setUnvanished(target);
        PlayerUtil.reset(true, player, target);
        contents.equip(player, target);
        arena.addPlayers(player, target);
        PlayerUtil.pm(StringUtil.replaceWithArgs(config.getString("on-request-accept-target"), "{PLAYER}", target.getName(), "{KIT}", request.getKit()).replace("{ARENA}", arenaName), player);
        PlayerUtil.pm(StringUtil.replaceWithArgs(config.getString("on-request-accept-sender"), "{PLAYER}", player.getName(), "{KIT}", request.getKit()).replace("{ARENA}", arenaName), target);

        if (arenaManager.getGUI() != null) {
            arenaManager.getGUI().update(arenaManager.getArenas());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();

        if (player.hasMetadata("respawn")) {
            Respawn data = (Respawn) player.getMetadata("respawn").get(0).value();
            event.setRespawnLocation(data.getRespawnLocation());
            PlayerUtil.reset(player, false);
            PlayerUtil.setInventory(player, data.getInventoryData().getInventoryContents(), data.getInventoryData().getArmorContents(), false);
            player.removeMetadata("respawn", Core.getInstance());
            essentialsHook.setBackLocation(player, event.getRespawnLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();

        if (!arenaManager.isInMatch(dead)) {
            return;
        }

        event.setDeathMessage(null);
        event.setKeepLevel(true);
        event.setDroppedExp(0);
        event.getDrops().clear();

        final Arena arena = arenaManager.getArena(dead);
        final Arena.Match match = arena.getCurrentMatch();

        arena.removePlayer(dead.getUniqueId());

        Arena.InventoryData deadData = match.getInventories(dead.getUniqueId());
        final Location lobby = dataManager.getLobby() != null ? dataManager.getLobby() : dead.getWorld().getSpawnLocation();

        if (config.getBoolean("teleport-to-latest-location")) {
            dead.setMetadata("respawn", new Metadata(Core.getInstance(), new Respawn(deadData, match.getLocation(dead.getUniqueId()))));
        } else {
            dead.setMetadata("respawn", new Metadata(Core.getInstance(), new Respawn(deadData, lobby)));
        }

        if (!arena.isEmpty()) {
            final Player target = Bukkit.getPlayer(arena.getPlayers().get(0));
            Firework firework = (Firework) target.getWorld().spawnEntity(target.getEyeLocation(), EntityType.FIREWORK);
            FireworkMeta meta = firework.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder().withColor(Color.RED).with(FireworkEffect.Type.BALL_LARGE).withTrail().build());
            firework.setFireworkMeta(meta);
            Bukkit.broadcastMessage(StringUtil.color(config.getString("on-duel-end").replace("{WINNER}", target.getName()).replace("{LOSER}", dead.getName()).replace("{HEALTH}", String.valueOf(Math.round(target.getHealth()) * 0.5D))));

            Calendar calendar = new GregorianCalendar();
            match.setEndTimeMillis(System.currentTimeMillis());
            match.setFinishingHealth(Math.floor(target.getHealth()) * 0.5);
            MatchData matchData = new MatchData(target.getName(), dead.getName(), calendar.getTimeInMillis(), match.getDuration(), match.getFinishingHealth());
            UserData player = dataManager.getUser(dead.getUniqueId(), false);
            UserData opponent = dataManager.getUser(target.getUniqueId(), false);

            if (player != null && opponent != null) {
                player.addMatch(matchData);
                opponent.addMatch(matchData);
                player.edit(UserData.EditType.ADD, UserData.StatsType.LOSSES, 1);
                opponent.edit(UserData.EditType.ADD, UserData.StatsType.WINS, 1);
            }

            int delay = config.getInt("delay-until-teleport-on-win");
            final Location last = arena.getCurrentMatch().getLocation(target.getUniqueId());
            final Arena.InventoryData targetData = match.getInventories(target.getUniqueId());

            if (delay > 0) {
                new BukkitRunnable() {

                    @Override
                    public void run() {
                        arena.setUsed(false);
                        if (arenaManager.getGUI() != null) {
                            arenaManager.getGUI().update(arenaManager.getArenas());
                        }

                        if (!target.isOnline() || target.isDead()) {
                            return;
                        }

                        if (!config.getBoolean("teleport-to-latest-location")) {
                            if (!PlayerUtil.canTeleportTo(target, lobby)) {
                                target.setHealth(0.0D);
                                target.sendMessage(ChatColor.RED + "[Duels] Your teleportation to lobby was unsuccessful! Setting health to 0.0 just to not glitch the system by letting you stay in the arena. ;)");
                                return;
                            }

                            target.teleport(lobby);
                        } else {
                            target.teleport(last);
                        }

                        essentialsHook.setBackLocation(target, target.getLocation());
                        PlayerUtil.reset(target, true);

                        if (!config.getBoolean("requires-cleared-inventory")) {
                            PlayerUtil.setInventory(target, targetData.getInventoryContents(), targetData.getArmorContents(), false);
                        }

                    }
                }.runTaskLater(instance, delay * 20L);
            } else {
                arena.setUsed(false);

                if (arenaManager.getGUI() != null) {
                    arenaManager.getGUI().update(arenaManager.getArenas());
                }

                if (target.isDead()) {
                    target.spigot().respawn();
                    return;
                }

                if (!config.getBoolean("teleport-to-latest-location")) {
                    target.teleport(lobby);
                } else {
                    target.teleport(last);
                }

                essentialsHook.setBackLocation(target, target.getLocation());
                PlayerUtil.reset(target, true);

                if (!config.getBoolean("requires-cleared-inventory")) {
                    PlayerUtil.setInventory(target, targetData.getInventoryContents(), targetData.getArmorContents(), false);
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        if (!arenaManager.isInMatch(player)) {
            return;
        }

        player.setHealth(0.0D);
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();

        if (event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL || !arenaManager.isInMatch(player)) {
            return;
        }

        event.setCancelled(true);
        PlayerUtil.pm("&cYou may not teleport while in duel.", player);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();

        if (config.getBoolean("drop-item") || !arenaManager.isInMatch(player)) {
            return;
        }

        event.setCancelled(true);
        PlayerUtil.pm("&cYou may not drop items while in duel.", player);
    }

    @EventHandler
    public void onPickup(PlayerPickupItemEvent event) {
        Player player = event.getPlayer();

        if (config.getBoolean("pick-up-item") || !arenaManager.isInMatch(player)) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();

        if (config.getList("disabled-commands").isEmpty() || !arenaManager.isInMatch(player)) {
            return;
        }

        String command = event.getMessage().substring(1);

        if (config.getList("disabled-commands").contains(command.split(" ")[0].toLowerCase())) {
            event.setCancelled(true);
            PlayerUtil.pm("&cYou may not use that command while in duel.", player);
        }
    }

    @EventHandler
    public void onFlyToggle(PlayerToggleFlightEvent event) {
        if (event.isFlying() && arenaManager.isInMatch(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().setAllowFlight(false);
            PlayerUtil.pm("&cYou may not fly while in duel.", event.getPlayer());
        }
    }
}
