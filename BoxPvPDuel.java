package com.avenor.duel;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class BoxPvPDuel extends JavaPlugin implements Listener, CommandExecutor {

    private final Map<Player, Player> duelRequests = new HashMap<>();
    private final Map<Player, Player> activeDuels = new HashMap<>();
    private final Map<Player, String> playerArena = new HashMap<>();
    private final Map<Player, Integer> duelTimers = new HashMap<>();
    private final Map<Player, Inventory> lootMenus = new HashMap<>();
    private final Map<String, List<Location>> placedBlocks = new HashMap<>();
    private final Map<String, Boolean> arenaStatus = new HashMap<>();
    private final Map<Player, Location[]> wandSelections = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("arena").setExecutor(this);
        getCommand("duello").setExecutor(this);
        getCommand("kabul").setExecutor(this);
        getCommand("duelsandik").setExecutor(this);
        
        // Arenaları yükle
        if (getConfig().getConfigurationSection("arenas") != null) {
            for (String key : getConfig().getConfigurationSection("arenas").getKeys(false)) {
                arenaStatus.put(key, false);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;

        // ARENA KOMUTLARI
        if (label.equalsIgnoreCase("arena")) {
            if (!p.hasPermission("arena.admin")) return true;
            if (args.length == 0) {
                p.sendMessage("§e/arena <wand | olustur | list | setspawn>");
                return true;
            }

            if (args[0].equalsIgnoreCase("wand")) {
                ItemStack wand = new ItemStack(Material.GOLDEN_AXE);
                ItemMeta meta = wand.getItemMeta();
                meta.setDisplayName("§6§lArena Belirleyici");
                meta.setLore(Arrays.asList("§7Sol tık: §ePos 1", "§7Sağ tık: §ePos 2"));
                wand.setItemMeta(meta);
                p.getInventory().addItem(wand);
                p.sendMessage("§a[!] Arena wandı verildi.");
            } 
            else if (args[0].equalsIgnoreCase("olustur") && args.length > 1) {
                Location[] locs = wandSelections.get(p);
                if (locs == null || locs[0] == null || locs[1] == null) {
                    p.sendMessage("§cÖnce wand ile 2 nokta seçmelisin!");
                    return true;
                }
                getConfig().set("arenas." + args[1] + ".pos1", locs[0]);
                getConfig().set("arenas." + args[1] + ".pos2", locs[1]);
                saveConfig();
                arenaStatus.put(args[1], false);
                p.sendMessage("§a[!] " + args[1] + " arenası başarıyla oluşturuldu.");
            }
            else if (args[0].equalsIgnoreCase("list")) {
                p.sendMessage("§8§m-------§6§l ARENALAR §8§m-------");
                arenaStatus.forEach((name, status) -> {
                    String color = status ? "§a" : "§c"; // İstediğin gibi: Dolu=Yeşil, Boş=Kırmızı
                    p.sendMessage("§8» " + color + name + " §7(" + (status ? "DOLU" : "BOŞ") + ")");
                });
            }
            return true;
        }

        // DUELLO KOMUTLARI
        if (label.equalsIgnoreCase("duello") && args.length > 0) {
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null || target == p) return true;
            duelRequests.put(target, p);
            p.sendMessage("§e" + target.getName() + " §7kişisine istek atıldı.");
            target.sendMessage("§e" + p.getName() + " §7sana düello isteği attı! §a/kabul " + p.getName());
            return true;
        }

        if (label.equalsIgnoreCase("kabul") && args.length > 0) {
            Player req = Bukkit.getPlayer(args[0]);
            if (duelRequests.get(p) == req) {
                String arena = arenaStatus.entrySet().stream().filter(e -> !e.getValue()).map(Map.Entry::getKey).findFirst().orElse(null);
                if (arena == null) { p.sendMessage("§cBoş arena yok!"); return true; }
                startDuel(req, p, arena);
                duelRequests.remove(p);
            }
            return true;
        }

        if (label.equalsIgnoreCase("duelsandik")) {
            if (lootMenus.containsKey(p)) p.openInventory(lootMenus.get(p));
            else p.sendMessage("§cAçık loot sandığın yok.");
            return true;
        }

        return false;
    }

    private void startDuel(Player p1, Player p2, String arena) {
        arenaStatus.put(arena, true);
        activeDuels.put(p1, p2);
        activeDuels.put(p2, p1);
        playerArena.put(p1, arena);
        playerArena.put(p2, arena);
        duelTimers.put(p1, 600);

        p1.teleport(getConfig().getLocation("arenas." + arena + ".pos1"));
        p2.teleport(getConfig().getLocation("arenas." + arena + ".pos2"));

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!activeDuels.containsKey(p1)) { this.cancel(); return; }
                int time = duelTimers.get(p1);
                if (time <= 0) {
                    broadcastDuel("§eDüello berabere bitti!", p1, p2);
                    finishDuel(p1, p2, arena, null);
                    this.cancel();
                    return;
                }
                String msg = "§eKalan Süre: §6" + String.format("%02d:%02d", time/60, time%60) + " §8| §fRakip: §c" + p2.getName();
                p1.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(msg));
                p2.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(msg.replace(p2.getName(), p1.getName())));
                duelTimers.put(p1, time - 1);
            }
        }.runTaskTimer(this, 0, 20);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player vic = e.getEntity();
        if (!activeDuels.containsKey(vic)) return;
        Player kil = activeDuels.get(vic);
        String arena = playerArena.get(vic);

        Inventory inv = Bukkit.createInventory(null, 54, "§8" + vic.getName() + " Eşyaları");
        e.getDrops().forEach(i -> { if(i != null) inv.addItem(i); });
        e.getDrops().clear();
        lootMenus.put(kil, inv);

        kil.sendMessage("§aKazandın! 60 saniye loot süren başladı. §e/duelsandik");
        kil.openInventory(inv);
        finishDuel(vic, kil, arena, kil);
    }

    private void finishDuel(Player v, Player k, String arena, Player winner) {
        activeDuels.remove(v); activeDuels.remove(k);
        v.teleport(v.getWorld().getSpawnLocation());
        
        new BukkitRunnable() {
            @Override
            public void run() {
                if (winner != null) {
                    winner.closeInventory();
                    lootMenus.remove(winner);
                    winner.teleport(winner.getWorld().getSpawnLocation());
                    winner.sendMessage("§eLoot süresi bitti.");
                }
                if (placedBlocks.containsKey(arena)) {
                    placedBlocks.get(arena).forEach(l -> l.getBlock().setType(Material.AIR));
                    placedBlocks.get(arena).clear();
                }
                arenaStatus.put(arena, false);
            }
        }.runTaskLater(this, 20 * 60);
    }

    @EventHandler
    public void onWand(PlayerInteractEvent e) {
        if (e.getItem() != null && e.getItem().getType() == Material.GOLDEN_AXE && e.getClickedBlock() != null) {
            e.setCancelled(true);
            Location[] s = wandSelections.getOrDefault(e.getPlayer(), new Location[2]);
            if (e.getAction() == Action.LEFT_CLICK_BLOCK) { s[0] = e.getClickedBlock().getLocation(); e.getPlayer().sendMessage("§aPos 1 OK"); }
            else if (e.getAction() == Action.RIGHT_CLICK_BLOCK) { s[1] = e.getClickedBlock().getLocation(); e.getPlayer().sendMessage("§aPos 2 OK"); }
            wandSelections.put(e.getPlayer(), s);
        }
    }

    @EventHandler
    public void onBlock(BlockPlaceEvent e) {
        if (activeDuels.containsKey(e.getPlayer())) {
            placedBlocks.computeIfAbsent(playerArena.get(e.getPlayer()), k -> new ArrayList<>()).add(e.getBlock().getLocation());
        }
    }

    @EventHandler
    public void onCmd(PlayerCommandPreprocessEvent e) {
        if (activeDuels.containsKey(e.getPlayer()) && !e.getMessage().startsWith("/ec") && !e.getMessage().startsWith("/pv")) {
            e.setCancelled(true);
            e.getPlayer().sendMessage("§cSadece /ec ve /pv!");
        }
    }

    private void broadcastDuel(String m, Player p1, Player p2) {
        Bukkit.broadcastMessage("§8[§eDüello§8] " + m);
    }
}
