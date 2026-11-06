package dev.benim.hile;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.permissions.PermissionAttachment;

import java.util.*;

public class BenimHile extends JavaPlugin implements Listener {

    // ---- Ayarlar ----
    // BURAYA KENDI MINECRAFT ADINI YAZ (buyuk/kucuk harf farketmez)
    private static final String OWNER = "amhmet";
    private static final int XRAY_RADIUS = 12;       // blok yaricapi
    private static final double AURA_RANGE = 4.5;    // kill aura menzili
    private static final double AURA_DAMAGE = 8.0;   // vurus hasari (4 kalp)
    private static final boolean AURA_PLAYERS = false; // true yaparsan oyunculara da vurur

    private final Map<UUID, GameMode> noclip = new HashMap<>();
    private final Map<UUID, Set<Location>> xray = new HashMap<>();
    private final Set<UUID> aura = new HashSet<>();
    private final Map<UUID, PermissionAttachment> perms = new HashMap<>();

    private boolean isOwner(Player p) {
        return p.getName().equalsIgnoreCase(OWNER);
    }

    private void grant(Player p) {
        if (!isOwner(p) || perms.containsKey(p.getUniqueId())) return;
        PermissionAttachment a = p.addAttachment(this);
        a.setPermission("benimhile.use", true);
        perms.put(p.getUniqueId(), a);
        p.updateCommands();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        grant(e.getPlayer());
    }

    private final Set<Material> filler = EnumSet.noneOf(Material.class);
    private BlockData glass;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        for (Player op : getServer().getOnlinePlayers()) grant(op);
        glass = Material.GLASS.createBlockData();

        for (String n : new String[]{
                "STONE", "DEEPSLATE", "DIRT", "GRASS_BLOCK", "GRAVEL", "SAND", "SANDSTONE",
                "GRANITE", "DIORITE", "ANDESITE", "TUFF", "NETHERRACK", "BASALT", "BLACKSTONE",
                "COBBLESTONE", "COARSE_DIRT", "PODZOL", "MYCELIUM", "CALCITE", "DRIPSTONE_BLOCK",
                "SMOOTH_BASALT", "END_STONE", "RED_SAND", "TERRACOTTA", "CLAY", "MUD"}) {
            Material m = Material.getMaterial(n);
            if (m != null) filler.add(m);
        }

        // X-ray yenileme (2 sn'de bir)
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (UUID id : new ArrayList<>(xray.keySet())) {
                Player p = getServer().getPlayer(id);
                if (p != null && p.isOnline()) refreshXray(p);
            }
        }, 20L, 40L);

        // Kill aura (her 5 tick)
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (UUID id : aura) {
                Player p = getServer().getPlayer(id);
                if (p == null || !p.isOnline() || p.isDead()) continue;
                for (Entity e : p.getNearbyEntities(AURA_RANGE, AURA_RANGE, AURA_RANGE)) {
                    if (!(e instanceof LivingEntity le) || e.equals(p)) continue;
                    boolean ok = (e instanceof Enemy) || (AURA_PLAYERS && e instanceof Player);
                    if (!ok) continue;
                    le.damage(AURA_DAMAGE, p);
                }
            }
        }, 20L, 5L);
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(xray.keySet())) {
            Player p = getServer().getPlayer(id);
            if (p != null) disableXray(p);
        }
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (!(s instanceof Player p)) {
            s.sendMessage("Sadece oyun icinden kullanilir.");
            return true;
        }
        if (!isOwner(p)) {
            p.sendMessage("Unknown command. Type \"/help\" for help.");
            return true;
        }
        switch (cmd.getName().toLowerCase()) {
            case "noclip" -> toggleNoclip(p);
            case "xray" -> {
                if (xray.containsKey(p.getUniqueId())) disableXray(p);
                else { xray.put(p.getUniqueId(), new HashSet<>()); refreshXray(p); p.sendMessage("§aX-Ray AÇIK"); }
            }
            case "killaura" -> {
                if (aura.remove(p.getUniqueId())) p.sendMessage("§cKillAura KAPALI");
                else { aura.add(p.getUniqueId()); p.sendMessage("§aKillAura AÇIK"); }
            }
        }
        return true;
    }

    // ---- Noclip: spectator moduna gecip eski moda doner ----
    private void toggleNoclip(Player p) {
        GameMode old = noclip.remove(p.getUniqueId());
        if (old != null) {
            p.setGameMode(old);
            p.sendMessage("§cNoclip KAPALI");
        } else {
            noclip.put(p.getUniqueId(), p.getGameMode());
            p.setGameMode(GameMode.SPECTATOR);
            p.sendMessage("§aNoclip AÇIK (duvarlardan geçebilirsin)");
        }
    }

    // ---- X-Ray: dolgu bloklarini (sadece senin ekraninda) cama cevirir, cevherler gorunur kalir ----
    private void refreshXray(Player p) {
        Set<Location> old = xray.get(p.getUniqueId());
        if (old == null) return;
        Location c = p.getLocation();
        int cx = c.getBlockX(), cy = c.getBlockY(), cz = c.getBlockZ();
        var w = c.getWorld();

        Map<Location, BlockData> send = new HashMap<>();
        Set<Location> now = new HashSet<>();

        for (int x = cx - XRAY_RADIUS; x <= cx + XRAY_RADIUS; x++)
            for (int y = Math.max(w.getMinHeight(), cy - XRAY_RADIUS); y <= Math.min(w.getMaxHeight() - 1, cy + XRAY_RADIUS); y++)
                for (int z = cz - XRAY_RADIUS; z <= cz + XRAY_RADIUS; z++) {
                    if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                    Material m = w.getBlockAt(x, y, z).getType();
                    if (filler.contains(m)) {
                        Location l = new Location(w, x, y, z);
                        now.add(l);
                        if (!old.contains(l)) send.put(l, glass);
                    }
                }

        // Menzilden cikanlari eski haline dondur
        for (Location l : old) {
            if (!now.contains(l)) send.put(l, l.getBlock().getBlockData());
        }

        if (!send.isEmpty()) p.sendMultiBlockChange(send);
        xray.put(p.getUniqueId(), now);
    }

    private void disableXray(Player p) {
        Set<Location> old = xray.remove(p.getUniqueId());
        if (old != null && !old.isEmpty()) {
            Map<Location, BlockData> restore = new HashMap<>();
            for (Location l : old) restore.put(l, l.getBlock().getBlockData());
            p.sendMultiBlockChange(restore);
        }
        p.sendMessage("§cX-Ray KAPALI");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        perms.remove(id);
        GameMode old = noclip.remove(id);
        if (old != null) e.getPlayer().setGameMode(old);
        xray.remove(id);
        aura.remove(id);
    }
}
