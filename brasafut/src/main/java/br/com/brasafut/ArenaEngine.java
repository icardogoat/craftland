package br.com.brasafut;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ArenaEngine implements Listener {
    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private final Map<String, RunningMatch> matches = new LinkedHashMap<>();
    private final NamespacedKey ballKey;
    private BukkitTask tickTask;

    public ArenaEngine(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "arenas.yml");
        this.ballKey = new NamespacedKey(plugin, "ball");
    }

    public void enable() {
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void disable() {
        if (tickTask != null) tickTask.cancel();
        for (RunningMatch match : matches.values()) match.removeBall();
        matches.clear();
        save();
    }

    public Collection<Arena> all() {
        return arenas.values();
    }

    public Optional<Arena> get(String id) {
        return Optional.ofNullable(arenas.get(id.toLowerCase(Locale.ROOT)));
    }

    public Arena create(String id) {
        String key = id.toLowerCase(Locale.ROOT);
        Arena arena = new Arena(key);
        arenas.put(key, arena);
        save();
        return arena;
    }

    public boolean delete(String id) {
        Arena removed = arenas.remove(id.toLowerCase(Locale.ROOT));
        if (removed == null) return false;
        stop(id);
        save();
        return true;
    }

    public boolean setPoint(String id, String point, Location location) {
        Arena arena = arenas.get(id.toLowerCase(Locale.ROOT));
        if (arena == null) return false;
        switch (point.toLowerCase(Locale.ROOT)) {
            case "pos1" -> arena.pos1 = location.clone();
            case "pos2" -> arena.pos2 = location.clone();
            case "spawn" -> arena.ballSpawn = location.clone();
            case "timea" -> arena.teamASpawn = location.clone();
            case "timeb" -> arena.teamBSpawn = location.clone();
            case "gola1" -> arena.goalA1 = location.clone();
            case "gola2" -> arena.goalA2 = location.clone();
            case "golb1" -> arena.goalB1 = location.clone();
            case "golb2" -> arena.goalB2 = location.clone();
            default -> { return false; }
        }
        save();
        return true;
    }

    public boolean isReady(Arena a) {
        return a != null && a.pos1 != null && a.pos2 != null && a.ballSpawn != null
                && a.teamASpawn != null && a.teamBSpawn != null
                && a.goalA1 != null && a.goalA2 != null && a.goalB1 != null && a.goalB2 != null;
    }

    public StartResult start(String arenaId, String clubA, String clubB, Collection<? extends Player> aPlayers, Collection<? extends Player> bPlayers) {
        Arena arena = arenas.get(arenaId.toLowerCase(Locale.ROOT));
        if (arena == null) return new StartResult(false, "Arena não encontrada.");
        if (!isReady(arena)) return new StartResult(false, "A arena ainda não está configurada completamente.");
        if (matches.containsKey(arena.id)) return new StartResult(false, "Já existe uma partida nessa arena.");

        RunningMatch match = new RunningMatch(arena, clubA, clubB);
        matches.put(arena.id, match);
        for (Player p : aPlayers) {
            match.teamA.put(p.getUniqueId(), p.getName());
            p.teleport(arena.teamASpawn);
        }
        for (Player p : bPlayers) {
            match.teamB.put(p.getUniqueId(), p.getName());
            p.teleport(arena.teamBSpawn);
        }
        match.spawnBall();
        broadcast(match, "&6&lINÍCIO &8» &f" + clubA + " &e0 x 0 &f" + clubB);
        return new StartResult(true, "Partida iniciada.");
    }

    public void stop(String arenaId) {
        RunningMatch match = matches.remove(arenaId.toLowerCase(Locale.ROOT));
        if (match != null) {
            match.removeBall();
            broadcast(match, "&cPartida encerrada.");
        }
    }

    public Optional<RunningMatch> matchOf(Player player) {
        return matches.values().stream().filter(m -> m.teamA.containsKey(player.getUniqueId()) || m.teamB.containsKey(player.getUniqueId())).findFirst();
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        boolean kick = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
        boolean pass = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        if (!kick && !pass) return;
        Player p = event.getPlayer();
        RunningMatch match = matchOf(p).orElse(null);
        if (match == null || match.hitbox == null || !match.hitbox.isValid()) return;
        if (!isAimingAtBall(p, match.hitbox, 5.0)) return;
        event.setCancelled(true);
        shoot(match, p, kick ? 1.55 : 1.05, kick ? 0.22 : 0.12);
    }

    private boolean isAimingAtBall(Player p, Entity ball, double maxDistance) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Vector to = ball.getLocation().toVector().subtract(eye.toVector());
        double distance = to.length();
        if (distance > maxDistance || distance < 0.01) return false;
        double dot = dir.dot(to.clone().normalize());
        return dot > 0.92;
    }

    private void shoot(RunningMatch match, Player p, double power, double lift) {
        Vector direction = p.getEyeLocation().getDirection().normalize();
        Vector velocity = direction.multiply(power);
        velocity.setY(Math.max(lift, direction.getY() * 0.65 + lift));
        match.velocity = velocity;
        match.lastTouch = p.getUniqueId();
        p.getWorld().playSound(match.hitbox.getLocation(), Sound.ENTITY_SLIME_ATTACK, 0.9f, 1.35f);
        p.getWorld().spawnParticle(Particle.CRIT, match.hitbox.getLocation().add(0, .3, 0), 6, .15, .15, .15, .02);
    }

    private void tick() {
        for (RunningMatch match : matches.values().toArray(RunningMatch[]::new)) {
            if (match.hitbox == null || !match.hitbox.isValid()) {
                match.spawnBall();
                continue;
            }

            Location loc = match.hitbox.getLocation();
            if (!inside(match.arena.pos1, match.arena.pos2, loc)) {
                resetBall(match);
                continue;
            }

            if (inside(match.arena.goalA1, match.arena.goalA2, loc)) {
                match.scoreB++;
                goal(match, false);
                continue;
            }
            if (inside(match.arena.goalB1, match.arena.goalB2, loc)) {
                match.scoreA++;
                goal(match, true);
                continue;
            }

            // Física manual simples e estável entre versões.
            Vector v = match.velocity.clone();
            v.setY(v.getY() - 0.045);
            v.multiply(0.985);
            if (Math.abs(v.getX()) < 0.005) v.setX(0);
            if (Math.abs(v.getZ()) < 0.005) v.setZ(0);
            if (Math.abs(v.getY()) < 0.005) v.setY(0);

            Location next = loc.clone().add(v);
            if (next.getBlock().getType().isSolid()) {
                v.multiply(-0.55);
                v.setY(Math.abs(v.getY()) * 0.65 + 0.05);
                next = loc.clone().add(v);
            }
            match.velocity = v;
            match.hitbox.teleport(next);
            if (match.visual != null && match.visual.isValid()) {
                match.visual.teleport(next.clone().add(0, 0.15, 0));
            }

            // Domínio por aproximação: empurrão leve quando jogador corre na bola.
            for (Player p : players(match)) {
                if (!p.getWorld().equals(next.getWorld()) || p.getLocation().distanceSquared(next) > 2.0) continue;
                Vector push = p.getLocation().getDirection().setY(0).normalize().multiply(0.22);
                match.velocity.add(push);
                match.lastTouch = p.getUniqueId();
            }
        }
    }

    private void goal(RunningMatch match, boolean aScored) {
        String scorer = "";
        if (match.lastTouch != null) {
            Player p = Bukkit.getPlayer(match.lastTouch);
            if (p != null) scorer = " &7• &f" + p.getName();
        }
        String club = aScored ? match.clubA : match.clubB;
        broadcast(match, "&6&lGOOOL! &e" + club + scorer + " &8» &f" + match.clubA + " &e" + match.scoreA + " x " + match.scoreB + " &f" + match.clubB);
        for (Player p : players(match)) {
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 0.9f);
            p.sendTitle(ChatColor.GOLD + "GOL!", ChatColor.WHITE + club, 5, 30, 10);
        }
        resetPositions(match);
        resetBall(match);
    }

    private void resetPositions(RunningMatch match) {
        for (UUID id : match.teamA.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.teleport(match.arena.teamASpawn);
        }
        for (UUID id : match.teamB.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.teleport(match.arena.teamBSpawn);
        }
    }

    private void resetBall(RunningMatch match) {
        match.velocity = new Vector();
        match.lastTouch = null;
        if (match.hitbox != null && match.hitbox.isValid()) match.hitbox.teleport(match.arena.ballSpawn);
        if (match.visual != null && match.visual.isValid()) match.visual.teleport(match.arena.ballSpawn.clone().add(0, .15, 0));
    }

    private Collection<Player> players(RunningMatch match) {
        Map<UUID, Player> result = new LinkedHashMap<>();
        for (UUID id : match.teamA.keySet()) {
            Player p = Bukkit.getPlayer(id); if (p != null) result.put(id, p);
        }
        for (UUID id : match.teamB.keySet()) {
            Player p = Bukkit.getPlayer(id); if (p != null) result.put(id, p);
        }
        return result.values();
    }

    private void broadcast(RunningMatch match, String text) {
        String colored = ChatColor.translateAlternateColorCodes('&', "&6&lBrasaFut &8» &r" + text);
        for (Player p : players(match)) p.sendMessage(colored);
    }

    private static boolean inside(Location a, Location b, Location p) {
        if (a == null || b == null || p == null || a.getWorld() == null || p.getWorld() == null || !a.getWorld().equals(p.getWorld())) return false;
        double minX = Math.min(a.getX(), b.getX()), maxX = Math.max(a.getX(), b.getX());
        double minY = Math.min(a.getY(), b.getY()), maxY = Math.max(a.getY(), b.getY());
        double minZ = Math.min(a.getZ(), b.getZ()), maxZ = Math.max(a.getZ(), b.getZ());
        return p.getX() >= minX && p.getX() <= maxX && p.getY() >= minY && p.getY() <= maxY && p.getZ() >= minZ && p.getZ() <= maxZ;
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Arena a : arenas.values()) {
            String p = "arenas." + a.id + ".";
            saveLoc(y, p + "pos1", a.pos1); saveLoc(y, p + "pos2", a.pos2);
            saveLoc(y, p + "ballSpawn", a.ballSpawn); saveLoc(y, p + "teamASpawn", a.teamASpawn); saveLoc(y, p + "teamBSpawn", a.teamBSpawn);
            saveLoc(y, p + "goalA1", a.goalA1); saveLoc(y, p + "goalA2", a.goalA2); saveLoc(y, p + "goalB1", a.goalB1); saveLoc(y, p + "goalB2", a.goalB2);
        }
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Falha ao salvar arenas.yml: " + e.getMessage());
        }
    }

    public void load() {
        arenas.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("arenas");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            Arena a = new Arena(id);
            String p = "arenas." + id + ".";
            a.pos1 = loadLoc(y, p + "pos1"); a.pos2 = loadLoc(y, p + "pos2");
            a.ballSpawn = loadLoc(y, p + "ballSpawn"); a.teamASpawn = loadLoc(y, p + "teamASpawn"); a.teamBSpawn = loadLoc(y, p + "teamBSpawn");
            a.goalA1 = loadLoc(y, p + "goalA1"); a.goalA2 = loadLoc(y, p + "goalA2"); a.goalB1 = loadLoc(y, p + "goalB1"); a.goalB2 = loadLoc(y, p + "goalB2");
            arenas.put(id.toLowerCase(Locale.ROOT), a);
        }
    }

    private static void saveLoc(YamlConfiguration y, String path, Location l) {
        if (l == null || l.getWorld() == null) return;
        y.set(path + ".world", l.getWorld().getName()); y.set(path + ".x", l.getX()); y.set(path + ".y", l.getY()); y.set(path + ".z", l.getZ());
        y.set(path + ".yaw", l.getYaw()); y.set(path + ".pitch", l.getPitch());
    }

    private static Location loadLoc(YamlConfiguration y, String path) {
        String worldName = y.getString(path + ".world");
        if (worldName == null) return null;
        World w = Bukkit.getWorld(worldName); if (w == null) return null;
        return new Location(w, y.getDouble(path + ".x"), y.getDouble(path + ".y"), y.getDouble(path + ".z"), (float)y.getDouble(path + ".yaw"), (float)y.getDouble(path + ".pitch"));
    }

    public record StartResult(boolean success, String message) {}

    public static final class Arena {
        public final String id;
        public Location pos1, pos2, ballSpawn, teamASpawn, teamBSpawn, goalA1, goalA2, goalB1, goalB2;
        Arena(String id) { this.id = id; }
    }

    public final class RunningMatch {
        public final Arena arena;
        public final String clubA, clubB;
        public final Map<UUID, String> teamA = new LinkedHashMap<>(), teamB = new LinkedHashMap<>();
        public int scoreA, scoreB;
        private Slime hitbox;
        private ItemDisplay visual;
        private Vector velocity = new Vector();
        private UUID lastTouch;

        RunningMatch(Arena arena, String clubA, String clubB) { this.arena = arena; this.clubA = clubA; this.clubB = clubB; }

        void spawnBall() {
            removeBall();
            Location spawn = arena.ballSpawn;
            hitbox = spawn.getWorld().spawn(spawn, Slime.class, s -> {
                s.setSize(1); s.setAI(false); s.setSilent(true); s.setInvulnerable(true); s.setGravity(false); s.setInvisible(true);
                s.getPersistentDataContainer().set(ballKey, PersistentDataType.BYTE, (byte)1);
            });
            visual = spawn.getWorld().spawn(spawn.clone().add(0, .15, 0), ItemDisplay.class, d -> {
                d.setItemStack(new ItemStack(Material.SNOWBALL));
                d.setInvulnerable(true); d.setGravity(false);
                d.getPersistentDataContainer().set(ballKey, PersistentDataType.BYTE, (byte)1);
            });
        }

        void removeBall() {
            if (hitbox != null) hitbox.remove();
            if (visual != null) visual.remove();
            hitbox = null; visual = null;
        }
    }
}
