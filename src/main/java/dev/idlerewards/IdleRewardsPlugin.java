package dev.idlerewards;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class IdleRewardsPlugin extends JavaPlugin implements Listener, TabCompleter {

    /** Per-player runtime state. Nothing here is persisted — rewards restart on server restart. */
    private static final class State {
        long lastActivity = System.currentTimeMillis();
        boolean afk;
        int idleSeconds;      // seconds counted towards the next reward
        int rewardsToday;
        LocalDate day = LocalDate.now();
        boolean limitNotified;
        boolean zoneNotified;
        BossBar bar;
    }

    private record Zone(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        boolean contains(Location loc) {
            World w = loc.getWorld();
            if (w == null || !w.getName().equals(world)) return false;
            int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
            return x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2;
        }
    }

    private record Reward(String key, int chance, String display, List<String> commands) {}

    private final Map<UUID, State> states = new HashMap<>();
    private final Map<UUID, Location[]> selections = new HashMap<>();
    private final Map<String, Zone> zones = new HashMap<>();
    private final List<Reward> rewards = new ArrayList<>();
    private int totalChance;

    private int afkAfter, interval, maxPerDay;
    private boolean requireZone, lookCounts;
    private String progressDisplay;
    private List<String> disabledWorlds = List.of();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        load();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        getLogger().info("IdleRewards enabled (" + rewards.size() + " rewards, " + zones.size() + " zones)");
    }

    @Override
    public void onDisable() {
        for (State s : states.values()) hideBar(s);
        states.clear();
    }

    // ---------------------------------------------------------------- config

    private void load() {
        reloadConfig();
        var c = getConfig();
        afkAfter = Math.max(1, c.getInt("afk-after-seconds", 120));
        interval = Math.max(1, c.getInt("reward-interval-seconds", 300));
        maxPerDay = Math.max(0, c.getInt("max-rewards-per-day", 24));
        requireZone = c.getBoolean("require-zone", false);
        lookCounts = c.getBoolean("look-counts-as-activity", false);
        progressDisplay = c.getString("progress-display", "actionbar").toLowerCase(Locale.ROOT);
        disabledWorlds = c.getStringList("disabled-worlds");
        // A reload may switch the display away from bossbar — drop bars that are still up.
        for (State s : states.values()) hideBar(s);

        rewards.clear();
        totalChance = 0;
        ConfigurationSection rs = c.getConfigurationSection("rewards");
        if (rs != null) {
            for (String key : rs.getKeys(false)) {
                ConfigurationSection r = rs.getConfigurationSection(key);
                if (r == null) continue;
                int chance = Math.max(0, r.getInt("chance", 1));
                if (chance == 0) continue;
                rewards.add(new Reward(key, chance, r.getString("display", key), r.getStringList("commands")));
                totalChance += chance;
            }
        }
        if (rewards.isEmpty()) getLogger().warning("No rewards configured — players will earn nothing.");

        zones.clear();
        ConfigurationSection zs = c.getConfigurationSection("zones");
        if (zs != null) {
            for (String name : zs.getKeys(false)) {
                ConfigurationSection z = zs.getConfigurationSection(name);
                if (z == null) continue;
                String w = z.getString("world");
                if (w == null) { getLogger().warning("Zone '" + name + "' has no world — skipped."); continue; }
                zones.put(name.toLowerCase(Locale.ROOT), new Zone(w,
                        Math.min(z.getInt("x1"), z.getInt("x2")), Math.min(z.getInt("y1"), z.getInt("y2")),
                        Math.min(z.getInt("z1"), z.getInt("z2")), Math.max(z.getInt("x1"), z.getInt("x2")),
                        Math.max(z.getInt("y1"), z.getInt("y2")), Math.max(z.getInt("z1"), z.getInt("z2"))));
            }
        }
    }

    private String msg(String path) {
        return getConfig().getString("messages." + path, "");
    }

    private void send(CommandSender to, String path, String... kv) {
        String raw = msg(path);
        if (raw.isEmpty()) return;
        for (int i = 0; i + 1 < kv.length; i += 2) raw = raw.replace("{" + kv[i] + "}", kv[i + 1]);
        to.sendMessage(Msg.parse(msg("prefix") + raw));
    }

    // ---------------------------------------------------------------- activity

    private State state(Player p) {
        return states.computeIfAbsent(p.getUniqueId(), k -> new State());
    }

    /** Any of these resets the idle timer and ends AFK. */
    private void active(Player p) {
        State s = state(p);
        s.lastActivity = System.currentTimeMillis();
        if (s.afk) {
            s.afk = false;
            s.idleSeconds = 0;
            s.limitNotified = false;
            s.zoneNotified = false;
            hideBar(s);
            send(p, "no-longer-afk");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        var from = e.getFrom();
        var to = e.getTo();
        boolean moved = from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ();
        boolean looked = lookCounts && (from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch());
        if (moved || looked) active(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) { active(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { active(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { active(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) { active(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) active(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) { active(e.getPlayer()); }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { states.put(e.getPlayer().getUniqueId(), new State()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        State s = states.remove(e.getPlayer().getUniqueId());
        if (s != null) hideBar(s);
        selections.remove(e.getPlayer().getUniqueId());
    }

    // ---------------------------------------------------------------- ticking

    private void tick() {
        long now = System.currentTimeMillis();
        LocalDate today = LocalDate.now();
        for (Player p : Bukkit.getOnlinePlayers()) {
            State s = state(p);
            if (!s.day.equals(today)) {
                s.day = today;
                s.rewardsToday = 0;
                s.limitNotified = false;
            }
            if (disabledWorlds.contains(p.getWorld().getName()) || !p.hasPermission("idlerewards.use")) {
                hideBar(s);
                continue;
            }
            boolean idle = now - s.lastActivity >= afkAfter * 1000L;
            if (!idle) { hideBar(s); continue; }
            if (!s.afk) {
                s.afk = true;
                s.idleSeconds = 0;
                s.zoneNotified = false;
                send(p, "now-afk");
            }
            if (requireZone && !inZone(p.getLocation())) {
                s.idleSeconds = 0;
                hideBar(s);
                if (!s.zoneNotified) {
                    s.zoneNotified = true;
                    send(p, "zone-required");
                }
                continue;
            }
            s.zoneNotified = false;
            if (maxPerDay > 0 && s.rewardsToday >= maxPerDay) {
                if (!s.limitNotified) {
                    s.limitNotified = true;
                    send(p, "daily-limit", "limit", String.valueOf(maxPerDay));
                }
                hideBar(s);
                continue;
            }
            s.idleSeconds++;
            if (s.idleSeconds >= interval) {
                s.idleSeconds = 0;
                grant(p, s);
            } else {
                showProgress(p, s);
            }
        }
    }

    private boolean inZone(Location loc) {
        for (Zone z : zones.values()) if (z.contains(loc)) return true;
        return false;
    }

    private void grant(Player p, State s) {
        if (rewards.isEmpty() || totalChance <= 0) return;
        int roll = ThreadLocalRandom.current().nextInt(totalChance);
        Reward picked = rewards.get(rewards.size() - 1);
        for (Reward r : rewards) {
            roll -= r.chance();
            if (roll < 0) { picked = r; break; }
        }
        int times = multiplier(p);
        for (int i = 0; i < times; i++) {
            for (String cmd : picked.commands()) {
                String line = cmd.replace("%player%", p.getName());
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
                } catch (Exception ex) {
                    getLogger().warning("Reward command failed: " + line + " (" + ex.getMessage() + ")");
                }
            }
        }
        s.rewardsToday++;
        String display = picked.display() + (times > 1 ? " x" + times : "");
        send(p, "reward-given", "reward", display);
    }

    /** idlerewards.multiplier.<n>, highest match from 10 down to 2 wins. */
    private int multiplier(Player p) {
        for (int n = 10; n >= 2; n--) {
            if (p.hasPermission("idlerewards.multiplier." + n)) return n;
        }
        return 1;
    }

    private void showProgress(Player p, State s) {
        int left = interval - s.idleSeconds;
        if (progressDisplay.equals("actionbar")) {
            String raw = msg("progress-actionbar").replace("{seconds}", String.valueOf(left));
            if (!raw.isEmpty()) p.sendActionBar(Msg.parse(raw));
        } else if (progressDisplay.equals("bossbar")) {
            float progress = Math.min(1f, Math.max(0f, (float) s.idleSeconds / interval));
            Component title = Msg.parse(msg("progress-bossbar").replace("{seconds}", String.valueOf(left)));
            if (s.bar == null) {
                s.bar = BossBar.bossBar(title, progress, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
                p.showBossBar(s.bar);
            } else {
                s.bar.name(title);
                s.bar.progress(progress);
            }
        }
    }

    private void hideBar(State s) {
        if (s.bar == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(s.bar);
        s.bar = null;
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Msg.parse(msg("prefix") + "<gray>/ir status · /ir reload · /ir pos1 · /ir pos2 · /ir zone create|remove|list</gray>"));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("status")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
            State s = state(p);
            send(p, "status", "afk", String.valueOf(s.afk), "idle", String.valueOf(s.idleSeconds),
                    "today", String.valueOf(s.rewardsToday));
            return true;
        }

        if (!sender.hasPermission("idlerewards.admin")) { send(sender, "no-permission"); return true; }

        switch (sub) {
            case "reload" -> {
                load();
                send(sender, "reloaded");
            }
            case "pos1", "pos2" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
                Location[] sel = selections.computeIfAbsent(p.getUniqueId(), k -> new Location[2]);
                int n = sub.equals("pos1") ? 0 : 1;
                sel[n] = p.getLocation();
                send(p, "pos-set", "n", String.valueOf(n + 1));
            }
            case "zone" -> handleZone(sender, args);
            default -> sender.sendMessage(Msg.parse(msg("prefix") + "<red>Unknown subcommand.</red>"));
        }
        return true;
    }

    private void handleZone(CommandSender sender, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        if (action.equals("list")) {
            sender.sendMessage(Msg.parse(msg("prefix") + "<gray>Zones: <white>"
                    + (zones.isEmpty() ? "none" : String.join(", ", zones.keySet())) + "</white></gray>"));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(Msg.parse(msg("prefix") + "<red>Usage: /ir zone create|remove <name></red>"));
            return;
        }
        String name = args[2].toLowerCase(Locale.ROOT);
        if (action.equals("remove")) {
            if (!zones.containsKey(name)) { send(sender, "zone-missing", "name", name); return; }
            zones.remove(name);
            getConfig().set("zones." + name, null);
            saveConfig();
            send(sender, "zone-removed", "name", name);
            return;
        }
        if (!action.equals("create")) {
            sender.sendMessage(Msg.parse(msg("prefix") + "<red>Usage: /ir zone create|remove|list</red>"));
            return;
        }
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return; }
        Location[] sel = selections.get(p.getUniqueId());
        if (sel == null || sel[0] == null || sel[1] == null || sel[0].getWorld() == null
                || !sel[0].getWorld().equals(sel[1].getWorld())) {
            send(p, "pos-missing");
            return;
        }
        String path = "zones." + name + ".";
        getConfig().set(path + "world", sel[0].getWorld().getName());
        getConfig().set(path + "x1", sel[0].getBlockX());
        getConfig().set(path + "y1", sel[0].getBlockY());
        getConfig().set(path + "z1", sel[0].getBlockZ());
        getConfig().set(path + "x2", sel[1].getBlockX());
        getConfig().set(path + "y2", sel[1].getBlockY());
        getConfig().set(path + "z2", sel[1].getBlockZ());
        saveConfig();
        load();
        send(p, "zone-created", "name", name);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) return List.of("status", "reload", "pos1", "pos2", "zone");
        if (args.length == 2 && args[0].equalsIgnoreCase("zone")) return List.of("create", "remove", "list");
        if (args.length == 3 && args[0].equalsIgnoreCase("zone") && args[1].equalsIgnoreCase("remove"))
            return new ArrayList<>(zones.keySet());
        return List.of();
    }
}
