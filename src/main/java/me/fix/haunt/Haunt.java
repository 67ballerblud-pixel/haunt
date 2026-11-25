package me.fix.haunt;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One haunting of one player. Everything here runs on that player's own scheduler (so it is Folia safe) and only
 * the haunted player ever sees or hears any of it.
 */
final class Haunt {

    enum Type { STALKER, BEHIND, RUSH, CROWD, WHISPER, SOUND, JUMPSCARE }

    private static final class Ghost {
        final Entity entity;
        final Type type;
        Location pos;
        int age;
        final int ttl;
        boolean closing;
        boolean turned;
        int hideAt = Integer.MAX_VALUE;

        Ghost(Entity entity, Type type, Location pos, int ttl) {
            this.entity = entity;
            this.type = type;
            this.pos = pos;
            this.ttl = ttl;
        }
    }

    private record Later(int due, Runnable action) {
    }

    private final HauntPlugin plugin;
    private final Ghosts ghosts;
    final UUID targetId;
    final String targetName;
    final int intensity;
    private final long endsAt;
    private final List<Ghost> live = new ArrayList<>();
    private final List<Later> later = new ArrayList<>();
    private ScheduledTask task;
    private int ticks;
    private long nextScare;
    private long nextAmbient;
    private long lastJumpscare;
    private int lastBeat;
    private boolean ended;

    Haunt(HauntPlugin plugin, Ghosts ghosts, Player target, int seconds, int intensity) {
        this.plugin = plugin;
        this.ghosts = ghosts;
        this.targetId = target.getUniqueId();
        this.targetName = target.getName();
        this.intensity = Math.max(1, Math.min(3, intensity));
        this.endsAt = System.currentTimeMillis() + seconds * 1000L;
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    private static Component legacy(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }

    // ------------------------------------------------------------------ lifecycle

    /** Call on the target's thread. */
    void start(Player p) {
        if (cfg().getBoolean("ambience.night", true)) {
            p.setPlayerTime(18000L, false);
            p.setPlayerWeather(WeatherType.DOWNFALL);
        }
        long now = System.currentTimeMillis();
        nextScare = now + 4000L;
        nextAmbient = now + 6000L;
        task = p.getScheduler().runAtFixedRate(plugin, t -> {
            try {
                tick(p);
            } catch (Throwable ex) {
                plugin.getLogger().warning("Haunt tick failed for " + targetName + ": " + ex);
                plugin.stopHaunt(targetId);
            }
        }, () -> end(null), 1L, 2L);
    }

    /** Call on the target's thread (p may be null if they are gone). */
    void end(Player p) {
        if (ended) return;
        ended = true;
        if (task != null) task.cancel();
        for (Ghost g : live) ghosts.remove(g.entity);
        live.clear();
        later.clear();
        if (p != null && p.isOnline()) {
            p.resetPlayerTime();
            p.resetPlayerWeather();
        }
    }

    boolean ended() {
        return ended;
    }

    /** Removes the fake players that are currently out (used after a world change). */
    void clearGhosts() {
        for (Ghost g : live) ghosts.remove(g.entity);
        live.clear();
    }

    int ghostCount() {
        return live.size();
    }

    private void tick(Player p) {
        if (ended) return;
        if (!p.isOnline() || p.isDead()) {
            plugin.stopHaunt(targetId);
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= endsAt) {
            plugin.stopHaunt(targetId);
            return;
        }
        ticks += 2;

        Iterator<Later> li = later.iterator();
        List<Runnable> due = new ArrayList<>();
        while (li.hasNext()) {
            Later l = li.next();
            if (l.due() <= ticks) {
                due.add(l.action());
                li.remove();
            }
        }
        for (Runnable r : due) r.run();

        updateGhosts(p);
        heartbeat(p);

        if (now >= nextScare) {
            Type t = pick();
            if (t != null) scare(p, t);
            int[] range = interval();
            nextScare = now + (range[0] + ThreadLocalRandom.current().nextInt(Math.max(1, range[1] - range[0] + 1))) * 1000L;
        }
        if (cfg().getBoolean("ambience.sounds", true) && now >= nextAmbient) {
            ambient(p);
            nextAmbient = now + (7 + ThreadLocalRandom.current().nextInt(14)) * 1000L;
        }
    }

    private void later(int inTicks, Runnable r) {
        later.add(new Later(ticks + inTicks, r));
    }

    private int[] interval() {
        List<Integer> l = cfg().getIntegerList("scares.interval." + intensity);
        if (l.size() >= 2) return new int[]{l.get(0), Math.max(l.get(0), l.get(1))};
        return new int[]{10, 25};
    }

    // ------------------------------------------------------------------ choosing a scare

    private Type pick() {
        Type[] types = {Type.STALKER, Type.BEHIND, Type.RUSH, Type.CROWD, Type.WHISPER, Type.SOUND};
        String[] keys = {"stalker", "behind", "rush", "crowd", "whisper", "sound"};
        int[] minIntensity = {1, 2, 2, 2, 1, 1};
        int total = 0;
        int[] w = new int[types.length];
        for (int i = 0; i < types.length; i++) {
            if (intensity < minIntensity[i]) continue;
            w[i] = Math.max(0, cfg().getInt("scares.weights." + keys[i], 10));
            total += w[i];
        }
        if (total <= 0) return null;
        int roll = ThreadLocalRandom.current().nextInt(total);
        for (int i = 0; i < types.length; i++) {
            roll -= w[i];
            if (roll < 0) return types[i];
        }
        return null;
    }

    void scare(Player p, Type t) {
        switch (t) {
            case STALKER -> stalker(p);
            case BEHIND -> behind(p);
            case RUSH -> rush(p);
            case CROWD -> crowd(p);
            case WHISPER -> whisper(p);
            case SOUND -> soundScare(p);
            case JUMPSCARE -> jumpscare(p);
        }
    }

    // ------------------------------------------------------------------ the scares

    private void stalker(Player p) {
        Location at = findSpot(p, 15, 24, true, Math.toRadians(70));
        if (at == null) at = findSpot(p, 12, 22, false, 0);
        if (at == null) return;
        addGhost(p, Type.STALKER, at, 500);
        p.playSound(p.getLocation(), Sound.AMBIENT_CAVE, 0.8f, 0.6f);
    }

    private void behind(Player p) {
        Vector flat = p.getEyeLocation().getDirection().setY(0);
        if (flat.lengthSquared() < 0.01) flat = new Vector(0, 0, 1);
        flat.normalize();
        Location at = null;
        for (double d : new double[]{2.6, 3.4, 4.5}) {
            Location probe = p.getLocation().subtract(flat.clone().multiply(d));
            at = groundAt(p.getWorld(), probe.getX(), p.getLocation().getY(), probe.getZ());
            if (at != null) break;
        }
        if (at == null) return;
        addGhost(p, Type.BEHIND, at, 90);
        p.playSound(at, Sound.ENTITY_PLAYER_BREATH, 0.9f, 0.7f);
    }

    private void rush(Player p) {
        Location at = findSpot(p, 16, 22, true, Math.toRadians(55));
        if (at == null) return;
        addGhost(p, Type.RUSH, at, 120);
        p.playSound(at, Sound.ENTITY_WARDEN_NEARBY_CLOSER, 1.0f, 0.8f);
    }

    private void crowd(Player p) {
        int n = 4 + intensity * 3;
        int made = 0;
        for (int i = 0; i < n * 2 && made < n; i++) {
            Location at = findSpot(p, 8, 13, false, 0);
            if (at == null) continue;
            if (addGhost(p, Type.CROWD, at, 140 + ThreadLocalRandom.current().nextInt(40)) != null) made++;
        }
        if (made > 0) p.playSound(p.getLocation(), Sound.BLOCK_BELL_RESONATE, 0.6f, 0.5f);
    }

    private void whisper(Player p) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int kind = r.nextInt(3);
        List<String> chat = cfg().getStringList("messages.chat");
        List<String> bar = cfg().getStringList("messages.action-bar");
        List<String> join = cfg().getStringList("messages.fake-join");
        if (kind == 0 && !chat.isEmpty()) {
            p.sendMessage(legacy(chat.get(r.nextInt(chat.size()))));
        } else if (kind == 1 && !bar.isEmpty()) {
            p.sendActionBar(legacy(bar.get(r.nextInt(bar.size()))));
        } else if (!join.isEmpty()) {
            p.sendMessage(Component.text(join.get(r.nextInt(join.size())), NamedTextColor.YELLOW));
        }
        p.playSound(p.getLocation(), Sound.AMBIENT_CAVE, 0.7f, 0.5f);
    }

    private void soundScare(Player p) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Vector flat = p.getEyeLocation().getDirection().setY(0);
        if (flat.lengthSquared() < 0.01) flat = new Vector(0, 0, 1);
        flat.normalize();
        int kind = r.nextInt(4);
        if (kind == 0) {
            // footsteps creeping up from behind
            for (int i = 0; i < 7; i++) {
                final double dist = 9 - i * 1.0;
                final Vector back = flat.clone();
                final boolean gravel = i % 2 == 0;
                later(i * 7, () -> {
                    Location at = p.getLocation().subtract(back.clone().multiply(dist));
                    p.playSound(at, gravel ? Sound.BLOCK_GRAVEL_STEP : Sound.BLOCK_STONE_STEP, 0.9f, 0.8f);
                });
            }
        } else if (kind == 1) {
            Location at = p.getLocation().add(r.nextDouble(-8, 8), 0, r.nextDouble(-8, 8));
            p.playSound(at, Sound.BLOCK_WOODEN_DOOR_OPEN, 1.0f, 0.8f);
            later(24, () -> p.playSound(at, Sound.BLOCK_WOODEN_DOOR_CLOSE, 1.0f, 0.8f));
        } else if (kind == 2) {
            Location at = p.getLocation().add(r.nextDouble(-6, 6), 0, r.nextDouble(-6, 6));
            p.playSound(at, Sound.BLOCK_CHEST_OPEN, 1.0f, 0.7f);
        } else {
            Location at = p.getLocation().subtract(flat.clone().multiply(2.5));
            p.playSound(at, Sound.ENTITY_ENDERMAN_STARE, 0.7f, 0.6f);
        }
    }

    private void ambient(Player p) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Sound[] pool = {Sound.AMBIENT_CAVE, Sound.ENTITY_PHANTOM_AMBIENT, Sound.ENTITY_ZOMBIE_AMBIENT, Sound.ENTITY_VEX_AMBIENT};
        Sound s = pool[r.nextInt(pool.length)];
        Location at = p.getLocation().add(r.nextDouble(-14, 14), 0, r.nextDouble(-14, 14));
        p.playSound(at, s, 0.5f, 0.5f + (float) r.nextDouble(0.3));
    }

    /** The big scare: sound, skull flash and a darkness pulse. Has a cooldown so it can't be spammed. */
    boolean jumpscare(Player p) {
        long now = System.currentTimeMillis();
        if (now - lastJumpscare < cfg().getLong("scares.jumpscare.cooldown-seconds", 6) * 1000L) return false;
        lastJumpscare = now;
        String title = cfg().getString("scares.jumpscare.title", "&4&l\u2620");
        if (title != null && !title.isBlank()) {
            p.showTitle(Title.title(legacy(title), Component.empty(),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(500), Duration.ofMillis(300))));
        }
        p.playSound(p.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.0f, 0.7f);
        p.playSound(p.getLocation(), Sound.ENTITY_GHAST_SCREAM, 1.0f, 0.5f);
        if (intensity >= 2 && cfg().getBoolean("scares.jumpscare.darkness", true)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 50, 0, false, false, false));
        }
        if (intensity >= 3 && cfg().getBoolean("scares.jumpscare.blindness", false)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 14, 0, false, false, false));
        }
        if (cfg().getBoolean("scares.jumpscare.hurt-flash", false)) {
            p.playHurtAnimation(p.getLocation().getYaw());
        }
        return true;
    }

    // ------------------------------------------------------------------ ghosts

    private Ghost addGhost(Player p, Type type, Location at, int ttl) {
        if (live.size() >= cfg().getInt("scares.max-ghosts", 18)) return null;
        PlayerProfile profile = ghosts.pickProfile(p);
        Entity e = ghosts.spawn(at, profile, p);
        Ghost g = new Ghost(e, type, at.clone(), ttl);
        live.add(g);
        face(g, p.getEyeLocation());
        return g;
    }

    private void face(Ghost g, Location eye) {
        Vector v = eye.toVector().subtract(g.pos.toVector().add(new Vector(0, 1.6, 0)));
        Location l = g.pos.clone();
        l.setDirection(v);
        g.entity.setRotation(l.getYaw(), l.getPitch());
    }

    private void move(Ghost g, Location to) {
        g.pos = to.clone();
        g.entity.teleportAsync(to);
    }

    private void vanishFx(Player p, Location at, boolean loud) {
        Location c = at.clone().add(0, 1, 0);
        p.spawnParticle(Particle.SMOKE, c, 25, 0.3, 0.7, 0.3, 0.02);
        p.spawnParticle(Particle.SOUL, c, 6, 0.3, 0.6, 0.3, 0.01);
        if (loud) p.playSound(at, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 0.6f);
    }

    private void updateGhosts(Player p) {
        if (live.isEmpty()) return;
        Location eye = p.getEyeLocation();
        Vector look = eye.getDirection();
        Iterator<Ghost> it = live.iterator();
        while (it.hasNext()) {
            Ghost g = it.next();
            g.age += 2;
            boolean gone = !g.entity.isValid()
                    || g.pos.getWorld() != p.getWorld()
                    || g.pos.distanceSquared(p.getLocation()) > 80 * 80;
            if (!gone) gone = step(g, p, eye, look);
            if (gone) {
                ghosts.remove(g.entity);
                it.remove();
            }
        }
    }

    /** Advances one ghost. Returns true if it should be removed. */
    private boolean step(Ghost g, Player p, Location eye, Vector look) {
        Vector to = g.pos.toVector().add(new Vector(0, 1.6, 0)).subtract(eye.toVector());
        double dist = to.length();
        if (dist < 0.001) return true;
        Vector dir = to.clone().multiply(1.0 / dist);
        double facing = look.dot(dir);
        face(g, eye);

        switch (g.type) {
            case STALKER -> {
                boolean looked = facing > Math.cos(Math.toRadians(11)) && dist > 4 && g.age > 20 && p.hasLineOfSight(g.entity);
                if (looked) {
                    vanishFx(p, g.pos, true);
                    p.playSound(g.pos, Sound.ENTITY_ENDERMAN_STARE, 0.6f, 0.5f);
                    if (intensity >= 3 && ThreadLocalRandom.current().nextDouble() < 0.35) later(30, () -> behind(p));
                    return true;
                }
                if (g.age >= g.ttl) {
                    vanishFx(p, g.pos, false);
                    return true;
                }
            }
            case BEHIND -> {
                if (!g.turned && facing > Math.cos(Math.toRadians(55))) {
                    g.turned = true;
                    if (intensity >= 2) {
                        // you turned around... it is right in your face
                        Vector flat = look.clone().setY(0);
                        if (flat.lengthSquared() < 0.01) flat = new Vector(0, 0, 1);
                        flat.normalize();
                        Location inFront = p.getLocation().add(flat.multiply(1.5));
                        move(g, inFront);
                        face(g, eye);
                        jumpscare(p);
                        g.hideAt = g.age + 8;
                    } else {
                        vanishFx(p, g.pos, true);
                        return true;
                    }
                }
                if (g.age >= g.hideAt || g.age >= g.ttl) {
                    vanishFx(p, g.pos, true);
                    return true;
                }
            }
            case RUSH -> {
                if (dist <= 2.2) {
                    jumpscare(p);
                    vanishFx(p, g.pos, true);
                    return true;
                }
                double speed = cfg().getDouble("scares.rush-speed", 1.5);
                Vector flat = to.clone().setY(0).multiply(-1);
                if (flat.lengthSquared() > 0.0001) {
                    flat.normalize().multiply(speed);
                    Location next = g.pos.clone().add(flat);
                    Location ground = groundAt(next.getWorld(), next.getX(), p.getLocation().getY(), next.getZ());
                    if (ground != null) next = ground;
                    move(g, next);
                }
                if (g.age >= g.ttl) return true;
            }
            case CROWD -> {
                if (g.age >= g.ttl) g.closing = true;
                if (g.closing) {
                    Vector flat = to.clone().setY(0).multiply(-1);
                    if (flat.lengthSquared() > 0.0001) {
                        flat.normalize().multiply(0.45);
                        move(g, g.pos.clone().add(flat));
                    }
                    if (dist < 2.8) {
                        if (intensity >= 3) jumpscare(p);
                        vanishFx(p, g.pos, false);
                        return true;
                    }
                }
            }
            default -> {
                return g.age >= g.ttl;
            }
        }
        return false;
    }

    private void heartbeat(Player p) {
        if (live.isEmpty()) return;
        double nearest = Double.MAX_VALUE;
        for (Ghost g : live) nearest = Math.min(nearest, g.pos.distance(p.getLocation()));
        if (nearest > 14) return;
        int gap = (int) Math.max(8, nearest * 2 + 6);
        if (ticks - lastBeat >= gap) {
            lastBeat = ticks;
            p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 0.8f, 1.0f);
        }
    }

    /** The target hit a ghost: it vanishes (and sometimes comes back behind them). */
    void hit(Player p, Entity e) {
        Iterator<Ghost> it = live.iterator();
        while (it.hasNext()) {
            Ghost g = it.next();
            if (g.entity.equals(e)) {
                vanishFx(p, g.pos, true);
                ghosts.remove(g.entity);
                it.remove();
                if (intensity >= 2 && ThreadLocalRandom.current().nextDouble() < 0.4) later(40, () -> behind(p));
                return;
            }
        }
    }

    // ------------------------------------------------------------------ finding places

    private Location findSpot(Player p, double minR, double maxR, boolean inFront, double spreadRad) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location c = p.getLocation();
        Vector look = p.getEyeLocation().getDirection();
        double base = Math.atan2(look.getZ(), look.getX());
        for (int i = 0; i < 14; i++) {
            double ang = inFront ? base + (r.nextDouble() * 2 - 1) * spreadRad : r.nextDouble() * Math.PI * 2;
            double rad = minR + r.nextDouble() * (maxR - minR);
            Location g = groundAt(p.getWorld(), c.getX() + Math.cos(ang) * rad, c.getY(), c.getZ() + Math.sin(ang) * rad);
            if (g != null) return g;
        }
        return null;
    }

    /** Standing position (solid block below, two free blocks above) near the given height, or null. */
    private static Location groundAt(World w, double x, double yHint, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return null;
        int top = (int) Math.floor(yHint) + 4;
        int bottom = (int) Math.floor(yHint) - 5;
        for (int y = top; y >= bottom; y--) {
            Block b = w.getBlockAt(bx, y, bz);
            if (b.getType().isSolid() && !b.isLiquid()) {
                Block a1 = w.getBlockAt(bx, y + 1, bz);
                Block a2 = w.getBlockAt(bx, y + 2, bz);
                if (a1.isPassable() && a2.isPassable() && !a1.isLiquid()) {
                    return new Location(w, bx + 0.5, y + 1, bz + 0.5);
                }
            }
        }
        return null;
    }
}
