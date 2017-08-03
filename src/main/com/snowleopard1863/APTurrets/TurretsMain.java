package com.snowleopard1863.APTurrets;

import net.milkbowl.vault.economy.Economy;
import net.minecraft.server.v1_10_R1.PacketPlayOutEntityDestroy;
import org.bukkit.*;
import org.bukkit.block.Sign;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.craftbukkit.v1_10_R1.entity.CraftPlayer;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public final class TurretsMain extends JavaPlugin implements Listener {
    private PluginDescriptionFile pdfile = getDescription();
    private Logger logger = Logger.getLogger("Minecraft");
    private List<Player> onTurrets = new ArrayList<Player>();
    private List<Player> reloading = new ArrayList<Player>();
    private List<Arrow> tracedArrows = new ArrayList<Arrow>();
    private Boolean Debug = false;
    private FileConfiguration config = getConfig();
    private boolean takeFromInventory, takeFromChest, requireAmmo;
    private double costToPlace, damage, incindiaryChance, arrowVelocity;
    private int knockbackStrength;
    private boolean useParticleTracers;
    private double delayBetweenShots;
    private static Economy economy;

    @Override
    public void onEnable() {
        //Basic Setup
        Plugin p = this;
        logger.info(pdfile.getName() + " v" + pdfile.getVersion() + " has been enbaled.");
        getServer().getPluginManager().registerEvents(this, this);
        //
        // Sets Default Config Values if None Exist
        //
        config.addDefault("Debug mode", false);
        config.addDefault("Cost to Place", 15000.00);
        config.addDefault("Take arrows from inventory", true);
        config.addDefault("Take arrows from chest", true);
        config.addDefault("Require Ammo", true);
        config.addDefault("Damage per arrow", 2.5);
        config.addDefault("Incindiary chance", 0.10);
        config.addDefault("Knockback strength", 2);
        config.addDefault("Arrow velocity", 4.0);
        config.addDefault("Particle tracers", true);
        config.addDefault("Delay between shots", 0.2);
        config.options().copyDefaults(true);
        this.saveConfig();
        //
        // Loads Config Values If They Exist
        //
        Debug = getConfig().getBoolean("Debug mode");
        takeFromChest = getConfig().getBoolean("Take arrows from chest");
        takeFromInventory = getConfig().getBoolean("Take arrows from inventory");
        costToPlace = getConfig().getDouble("Cost to Place");
        requireAmmo = getConfig().getBoolean("Require Ammo");
        knockbackStrength = getConfig().getInt("Knockback strength");
        incindiaryChance = getConfig().getDouble("Incindiary chance");
        damage = getConfig().getDouble("Damage per arrow");
        arrowVelocity = getConfig().getDouble("Arrow velocity");
        useParticleTracers = getConfig().getBoolean("Particle tracers");
        delayBetweenShots = getConfig().getDouble("Delay between shots");
        //
        // Vault Support
        //
        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp != null) {
                economy = rsp.getProvider();
                logger.info("Found a compatible Vault plugin.");
            } else {
                logger.info("[WARNING] Could not find compatible Vault plugin. Disabling Vault integration.");
                economy = null;
            }
        } else {
            logger.info("Could not find compatible Vault plugin. Disabling Vault integration.");
            economy = null;
        }

        if (useParticleTracers) {
            getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
                public void run() {
                    for (Arrow a : tracedArrows) {
                        if (a.isOnGround() || a.isDead() || a.getTicksLived() > 100) {
                            a.removeMetadata("tracer", p);
                            tracedArrows.remove(a);
                            a.remove();
                        }
                        World world = a.getWorld();
                        world.spawnParticle(Particle.CRIT, a.getLocation(), 3, 0.0, 0.0, 0.0, 0);
                    }
                }
            }, 0, 0);
        }

    }

    @Override
    public void onLoad() {
        super.onLoad();
        logger = getLogger();
    }

    @Override
    public void onDisable() {
        for (Player player : onTurrets) {
            demount(player, player.getLocation());
            onTurrets.remove(player);
        }
        reloading.clear();
        tracedArrows.clear();
        logger.info(pdfile.getName() + " v" + pdfile.getVersion() + " has been disabled.");
    }

    //
    //
    // Handles Players Mounting
    //
    //
    @EventHandler
    public void onClick(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (Debug == true) {
                logger.info(event.getPlayer() + " has right clicked");
            }
            Player player = event.getPlayer();
            if (onTurrets.contains(player) && player.hasPermission("ap-turrets.use")) {
                if (Debug == true) {
                    logger.info(event.getPlayer() + " is on a turret");
                }
                //Prevents Players From Drinking Milk Buckets to Clear Potion Effects
                if (player.getInventory().getItemInMainHand().getType() == Material.MILK_BUCKET || player.getInventory().getItemInOffHand().getType() == Material.MILK_BUCKET) {
                    if (Debug == true) {
                        logger.info(event.getPlayer() + " has right clicked a milk bucket!");
                    }
                    event.setCancelled(true);
                }
                //
                // Fires Turret If Player Is Holding Button and Has Ammo and Is Not Reloading
                //

                if ((player.getInventory().getItemInMainHand().getType() == Material.STONE_BUTTON
                        || player.getInventory().getItemInOffHand().getType() == Material.STONE_BUTTON)) {

                    if (reloading.contains(player)) {
                        return;
                    } else if (player.getInventory().contains(Material.ARROW) && requireAmmo) {
                        fireTurret(player);
                        event.setCancelled(true);
                        if (Debug == true) {
                            logger.info(event.getPlayer() + " has shot");
                        }
                    } else if (player.getInventory().contains(Material.ARROW) != true) {
                        reloading.add(player);
                        Bukkit.getScheduler().scheduleSyncDelayedTask(this, new Runnable() {
                            public void run() {
                                reloading.remove(player);
                            }
                        }, (int) (delayBetweenShots * 10));
                        World world = player.getWorld();
                        world.playSound(player.getLocation(), Sound.BLOCK_DISPENSER_FAIL, 1, 2);
                        event.setCancelled(true);
                        if (Debug == true) {
                            logger.info(event.getPlayer() + " is out of ammo");
                        }
                    }
                }
            }
        }

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (Debug == true) {
                logger.info("A block has been right clicked");
            }
            if (event.getClickedBlock().getType() == Material.SIGN_POST
                    || event.getClickedBlock().getType() == Material.WALL_SIGN
                    || event.getClickedBlock().getType() == Material.SIGN) {
                if (Debug == true) {
                    logger.info("A sign was clicked");
                }
                Sign sign = (Sign) event.getClickedBlock().getState();
                if ("Mounted".equalsIgnoreCase(sign.getLine(0)) && "Gun".equalsIgnoreCase(sign.getLine(1))) {
                    if (Debug == true) {
                        logger.info("A Mounted Gun sign has been clicked");
                    }
                    Location signPos = event.getClickedBlock().getLocation();
                    signPos.setPitch(event.getPlayer().getLocation().getPitch());
                    signPos.setDirection(event.getPlayer().getVelocity());
                    mount(event.getPlayer(), signPos);
                }
            }
        }

        if (event.getAction() == Action.LEFT_CLICK_BLOCK && event.getPlayer().getInventory().getItemInMainHand().getType() == Material.STONE_BUTTON) {
            if (Debug == true) {
                logger.info("A block has been left clicked while holding a button");
            }
            if (event.getClickedBlock().getType() == Material.SIGN_POST
                    || event.getClickedBlock().getType() == Material.WALL_SIGN
                    || event.getClickedBlock().getType() == Material.SIGN) {
                if (Debug == true) {
                    logger.info("A sign was left clicked");
                }
                Sign sign = (Sign) event.getClickedBlock().getState();
                if ("Mounted".equalsIgnoreCase(sign.getLine(0)) && "Gun".equalsIgnoreCase(sign.getLine(1))) {
                    if (Debug == true) {
                        logger.info("A Mounted Gun sign has been left clicked");
                    }
                    event.setCancelled(true);
                    if (!sign.getLine(3).equals(""))
                        sendMessage(event.getPlayer(), "\n" +
                                ChatColor.GOLD + "Type: " + ChatColor.BLACK + sign.getLine(3) + "\n" +
                                ChatColor.GOLD + "Damage/Shot: " + ChatColor.GRAY + damage + "\n" +
                                ChatColor.GOLD + "Delay Between Shots: " + ChatColor.GRAY + delayBetweenShots + "\n" +
                                ChatColor.GOLD + "Velocity: " + ChatColor.GRAY + arrowVelocity + "\n" +
                                ChatColor.GOLD + "Fire Chance: " + ChatColor.GRAY + incindiaryChance * 100 + "%\n" +
                                ChatColor.GOLD + "Knockback: " + ChatColor.GRAY + knockbackStrength + "\n" +
                                ChatColor.GOLD + "Cost to Place: $" + ChatColor.GRAY + costToPlace
                        );
                    else
                        sendMessage(event.getPlayer(), "\n" +
                                ChatColor.GOLD + "Damage/Shot: " + ChatColor.GRAY + damage + "\n" +
                                ChatColor.GOLD + "Delay Between Shots: " + ChatColor.GRAY + delayBetweenShots + "\n" +
                                ChatColor.GOLD + "Velocity: " + ChatColor.GRAY + arrowVelocity + "\n" +
                                ChatColor.GOLD + "Fire Chance: " + ChatColor.GRAY + incindiaryChance * 100 + "%\n" +
                                ChatColor.GOLD + "Knockback: " + ChatColor.GRAY + knockbackStrength + "\n" +
                                ChatColor.GOLD + "Cost to Place: $" + ChatColor.GRAY + costToPlace
                        );
                }
            }
        }

    }

    //
    // Prevents Players From Mounting Entities To De-Mount A Gun.
    //
    @EventHandler
    public void onEntityInteract(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        if (onTurrets.contains(p) && (e.getRightClicked() instanceof Boat || e.getRightClicked() instanceof Horse)) {
            demount(p, p.getLocation());
            if (Debug) logger.info("Player: " + p.getName() + "Has Mounted An Entity.");
        }
    }

    @EventHandler
    public void eventSignChanged(SignChangeEvent event) {
        //get player who placed the sign
        Player player = event.getPlayer();
        //check if the sign matches the cases for a turret
        if ("Mounted".equalsIgnoreCase(event.getLine(0)) && "Gun".equalsIgnoreCase(event.getLine(1))) {
            //check if player has permission to place a turret, than check if they have enough money to place the sign
            if (player.hasPermission("ap-turrets.place")) {
                if (economy != null) {
                    if (economy.has(player, costToPlace)) {
                        //if true charge player a configurable amount and send a message
                        economy.withdrawPlayer(player, 15000);
                        sendMessage(player, "Turret Created!");
                        event.setLine(0, "Mounted");
                        event.setLine(1, "Gun");
                        if (Debug == true) {
                            logger.info("A Mounted Gun sign has been place");
                        }
                    } else {
                        if (Debug == true) {
                            logger.info("A Mounted Gun sign failed to place");
                        }
                        //if false, clear the sign and return a permision error
                        event.setCancelled(true);
                        sendMessage(player, "You Don't Have Enough Money To Place A Turret. Cost To Place: " + ChatColor.RED + costToPlace);
                    }
                } else {
                    sendMessage(player, "Turret Created!");
                    if (Debug == true) {
                        logger.info("A Mounted Gun sign has been placed for free due to no vault instalation");
                    }
                }
            } else {
                if (Debug == true) {
                    logger.info("A Mounted Gun sign failed to place");
                }
                //if false, clear the sign and return a permision error
                event.setCancelled(true);
                sendMessage(player, ChatColor.RED + "ERROR " + ChatColor.WHITE + "You Must Be Donor To Place Mounted Guns!");
            }
        }
    }


    public void fireTurret(Player player) {
        if (player.isGliding()) {
            demount(player, player.getLocation());
            return;
        }
        reloading.add(player);
        Bukkit.getScheduler().scheduleSyncDelayedTask(this, new Runnable() {
            public void run() {
                reloading.remove(player);
            }
        }, (int) (delayBetweenShots * 10));
        Arrow arrow = player.launchProjectile(Arrow.class);
        arrow.setShooter(player);
        arrow.setVelocity(player.getLocation().getDirection().multiply(arrowVelocity));
        arrow.setBounce(false);
        arrow.setMetadata("isTurretBullet", new FixedMetadataValue(this, true));
        arrow.setKnockbackStrength(knockbackStrength);
        double rand = Math.random();
        if (rand <= incindiaryChance) {
            arrow.setFireTicks(500);
        }
        if (useParticleTracers) {
            arrow.setMetadata("tracer", new FixedMetadataValue(this, true));
            tracedArrows.add(arrow);
            arrow.setCritical(false);

            PacketPlayOutEntityDestroy packet = new PacketPlayOutEntityDestroy(arrow.getEntityId());
            for (Player p : getServer().getOnlinePlayers())
                ((CraftPlayer) p).getHandle().playerConnection.sendPacket(packet);
        } else {
            arrow.setCritical(true);
        }
        World world = player.getWorld();
        world.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_BLAST, 1, 2);
        world.playEffect(player.getLocation(), Effect.MOBSPAWNER_FLAMES, 0);
        if (requireAmmo && takeFromInventory) {
            ItemStack stack = player.getInventory().getItem(player.getInventory().first(Material.ARROW)).clone();
            stack.setAmount(1);
            player.getInventory().removeItem(stack);
            player.updateInventory();
        }

        if (Debug) {
            logger.info("Mounted Gun Fired.");
        }
    }

    @EventHandler
    public void onPlayerToggleSneakEvent(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        if (Debug == true) {
            logger.info(player + " sneaked");
        }
        if (player.isSneaking() && onTurrets.contains(player)) {
            demount(player, player.getLocation());
            if (Debug == true) {
                logger.info(player + " got out of their turret");
            }
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler
    public void onHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof Arrow) {
            Arrow arrow = (Arrow) event.getEntity();
            if (arrow.hasMetadata("isTurretBullet")) {
                if (Debug) {
                    logger.info("A bullet has landed");
                }

                Location arrowLoc = arrow.getLocation();
                World world = event.getEntity().getWorld();
                Location l = arrowLoc.getBlock().getLocation();
                arrow.getWorld().playEffect(l, Effect.STEP_SOUND, world.getBlockTypeIdAt(l));
                world.playEffect(l, Effect.TILE_BREAK, l.subtract(0, 1, 0).getBlock().getTypeId(), 0);
            }
        }
    }

    @EventHandler
    public void onEntityDamageEvent(EntityDamageEvent event) {
        if (Debug == true) {
            logger.info("An entity was damaged");
        }

        if (event.getEntity() instanceof Player) {
            if (Debug == true) {
                logger.info("It was a player");
            }
            Player player = (Player) event.getEntity();
            if (onTurrets.contains(player)) {
                if (Debug == true) {
                    logger.info("on a turret");
                }
                demount(player, player.getLocation());
            }

            if (event.getEntity().hasMetadata("isTurretBullet")) {
                if (player.isGliding()) {
                    player.setGliding(false);
                    player.setSprinting(false);
                }
            }
        }
    }


    @EventHandler
    public void onEntityDamageByEntityEvent(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Arrow) {
            //if (event.getDamager().getCustomName() == "Bullet") {
            if (event.getDamager().hasMetadata("isTurretBullet")) {
                event.setDamage(damage);
                if (Debug == true) {
                    Arrow a = (Arrow) event.getDamager();
                    Player shooter = (Player) a.getShooter();
                    logger.info(event.getEntity() + " was shot by " + shooter.getName() + " for " + event.getDamage() + " It should be doing " + damage);
                }
            }
        }
    }

    public void mount(Player player, Location signPos) {
        if (signPos.getBlock().getType() == Material.SIGN || signPos.getBlock().getType() == Material.SIGN_POST
                || signPos.getBlock().getType() == Material.WALL_SIGN) {
            if (Debug == true) {
                logger.info("Sign detected");
            }
            Sign sign = (Sign) signPos.getBlock().getState();
            if (onTurrets.contains(player)) {
                sendMessage(player, "You May Only Have One Person Mounted Per Turret.");
                if (Debug == true) {
                    logger.info("1 player per turret");
                }
            } else {
                if (Debug == true) {
                    logger.info(player.getName() + " is now on a turret");
                }
                sign.setLine(2, player.getName());
                sign.update();
                onTurrets.add(player);
                signPos.add(0.5, 0, 0.5);
                signPos.setDirection(player.getEyeLocation().getDirection());
                player.teleport(signPos);
                player.setWalkSpeed(0);
                //player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 1000000, 6));
                player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP, 1000000, 200));
            }
        } else {
            logger.warning("Sign not found!");
        }
    }

    public void demount(Player player, Location signPos) {
        if (Debug == true) {
            logger.info(player.getName() + " is being taken off a turret");
        }
        onTurrets.remove(player);
        reloading.remove(player);
        if (signPos.getBlock().getType() == Material.SIGN || signPos.getBlock().getType() == Material.SIGN_POST
                || signPos.getBlock().getType() == Material.WALL_SIGN) {
            if (Debug == true) {
                logger.info("sign found and updated");
            }
            Sign sign = (Sign) signPos.getBlock().getState();
            sign.setLine(2, "");
            sign.update();
        } else {
            logger.warning("Sign not found!");
        }
        signPos.subtract(-0.5, 0, -0.5);
        player.setWalkSpeed(0.2f);
        //player.removePotionEffect(PotionEffectType.SLOW);
        player.removePotionEffect(PotionEffectType.JUMP);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        demount(e.getPlayer(), e.getPlayer().getLocation());
    }

    public void sendMessage(Player p, String message) {
        p.sendMessage(ChatColor.GRAY + "[" + ChatColor.DARK_RED + "APTurrets" + ChatColor.GRAY + "] " + ChatColor.WHITE + message);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        if (onTurrets.contains(e.getEntity().getKiller())) {
            e.setDeathMessage(e.getEntity().getDisplayName() + " was gunned down by " + e.getEntity().getKiller().getDisplayName());
        }
    }

}
