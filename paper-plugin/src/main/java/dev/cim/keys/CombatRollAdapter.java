package dev.cim.keys;

import dev.cim.hud.VirtualKeyEvent;
import dev.cim.keys.CombatRollRules.Body;
import dev.cim.keys.CombatRollRules.Enchants;
import dev.cim.keys.CombatRollRules.Loadout;
import dev.cim.keys.CombatRollRules.RollState;
import dev.cim.keys.CombatRollRules.Vec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/**
 * The Combat Roll key on the server: the roll decided by {@link CombatRollRules}, the charges and
 * recharge ticked once a server tick per player, and the roll's effects.
 *
 * <p>Effects on a roll, from the server handler and the client effect: the velocity is added
 * (the client's LocalPlayer.push), food exhaustion rises by 0.1, the roll invulnerability is set
 * (0 ticks by default), and every nearby player sees 15 POOF particles at the feet and hears the
 * roll sound. The player-animation the client plays (roll.json) is not reproduced.
 *
 * <p>Attributes come from the armour enchantments only (acrobat on the chest, longfooted on the
 * feet, multi_roll on the head), looked up by key {@code combat_roll:<name>}. Whether the layer's
 * Paper items carry these enchantments is not verified here.
 */
public final class CombatRollAdapter implements ModKeyAdapter, Listener {

    /** The KeyMapping name Combat Roll registers (Keybindings.roll, key R). */
    public static final String KEY = "keybinds.combat_roll.roll";
    /** The modded sound id Combat Roll plays for a roll (sounds.json). */
    static final String ROLL_SOUND = "combat_roll:roll";
    /** Fallback vanilla stand-in when the layer has no visual-sounds mapping for the roll. */
    static final String FALLBACK_SOUND = "minecraft:block.wood.place";
    /** RollEffect: 15 puffs per roll. */
    static final int PUFFS = 15;

    private final Plugin plugin;
    private final ModKeyBridge bridge;
    private final Map<UUID, RollState> states = new HashMap<>();
    private final Map<String, String> sounds;
    private final Random random = new Random();

    public CombatRollAdapter(Plugin plugin, ModKeyBridge bridge) {
        this.plugin = plugin;
        this.bridge = bridge;
        this.sounds = readSoundMapping(Path.of(System.getProperty("user.dir"), "c-imagine", "f2v", "visual-sounds.tsv"));
    }

    /** Runs the per-tick charge and invulnerability bookkeeping for every online player. */
    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    void tick() {
        Set<UUID> online = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
            states.computeIfAbsent(player.getUniqueId(), u -> new RollState()).tick(loadout(player));
        }
        states.keySet().retainAll(online);
    }

    @Override
    public void press(Player player, VirtualKeyEvent event) {
        RollState state = states.computeIfAbsent(player.getUniqueId(), u -> new RollState());
        AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
        double moveSpeed = speed == null ? 0.0 : speed.getValue();
        Body body = new Body(player.isOnGround(), player.getFoodLevel(),
                player.isSwimming() || player.getPose() == Pose.SWIMMING, player.isInsideVehicle(),
                player.isHandRaised() || player.isBlocking(), player.getAttackCooldown());
        boolean dead = player.isDead() || player.getHealth() <= 0.0;
        if (!CombatRollRules.gatesPass(state, moveSpeed, dead, body)) {
            return;
        }
        Loadout loadout = loadout(player);
        Location at = player.getLocation();
        Block below = player.getWorld().getBlockAt(
                (int) Math.floor(at.getX()), (int) Math.floor(at.getY()) - 1, (int) Math.floor(at.getZ()));
        boolean inWater = player.isInWater();
        // Approximation: the fluid height is not in the Bukkit API; a submerged eye counts as full.
        double waterHeight = inWater ? (player.getEyeLocation().getBlock().getType() == Material.WATER ? 1.0 : 0.5) : 0.0;
        MovementInput movement = bridge.movement(player);
        Vec impulse = CombatRollRules.impulse((float) at.getYaw(), movement.forwardImpulse(),
                movement.leftImpulse(), loadout.distance(),
                inWater, waterHeight, player.isInLava(), CombatRollRules.friction(below.getType().getKey().toString()));
        player.setVelocity(player.getVelocity().add(new Vector(impulse.x(), impulse.y(), impulse.z())));
        state.onRoll();
        state.startInvulnerability();
        player.setExhaustion(player.getExhaustion() + CombatRollRules.EXHAUST_ON_ROLL);
        effects(player);
    }

    /** RollEffect.playVisuals for everyone who can see the roller: puffs at the feet and the roll sound. */
    private void effects(Player player) {
        World world = player.getWorld();
        Location at = player.getLocation();
        String sound = sounds.getOrDefault(ROLL_SOUND, FALLBACK_SOUND);
        world.playSound(at, sound, SoundCategory.PLAYERS, 1.0f, 1.0f);
        double width = 0.6;   // player bounding box, Entity.getRandomX(1.5) uses the width
        double height = 1.8;  // Entity.getY(0.3 * gaussian) uses the height
        for (int i = 0; i < PUFFS; i++) {
            double dx = random.nextGaussian() * 0.02;
            double dy = random.nextGaussian() * 0.02;
            double dz = random.nextGaussian() * 0.02;
            double x = at.getX() + (2.0 * random.nextDouble() - 1.0) * width * 0.5 * 1.5;
            double y = at.getY() + height * (random.nextGaussian() * 0.3);
            double z = at.getZ() + (2.0 * random.nextDouble() - 1.0) * width * 0.5 * 1.5;
            // count 0 makes the offsets the particle velocity, as addParticle's dx/dy/dz.
            world.spawnParticle(Particle.POOF, x, y, z, 0, dx, dy, dz, 1.0);
        }
    }

    /** The enchantment levels on the worn armour, mapped to the three attributes. */
    Loadout loadout(Player player) {
        EntityEquipment equipment = player.getEquipment();
        if (equipment == null) {
            return CombatRollRules.loadout(new Enchants(0, 0, 0));
        }
        int longfooted = level(equipment.getBoots(), "longfooted");
        int acrobat = level(equipment.getChestplate(), "acrobat");
        int multi = level(equipment.getHelmet(), "multi_roll");
        return CombatRollRules.loadout(new Enchants(longfooted, acrobat, multi));
    }

    static int level(ItemStack item, String name) {
        if (item == null || item.getType().isAir()) {
            return 0;
        }
        for (Map.Entry<Enchantment, Integer> e : item.getEnchantments().entrySet()) {
            NamespacedKey key = e.getKey().getKey();
            if (key != null && key.getNamespace().equals("combat_roll") && key.getKey().equals(name)) {
                return e.getValue();
            }
        }
        return 0;
    }

    /** Invulnerability after a roll: a damage event on a player still inside the window is cancelled. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            RollState state = states.get(player.getUniqueId());
            if (state != null && state.invulnerableTicks() > 0) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    /** The F2V sound stand-in for a modded id, from c-imagine/f2v/visual-sounds.tsv (id TAB target TAB heuristic). */
    static Map<String, String> readSoundMapping(Path file) {
        Map<String, String> out = new HashMap<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] p = line.split("\t");
                if (p.length >= 2 && !p[0].isEmpty() && !p[1].isEmpty()) {
                    out.putIfAbsent(p[0], p[1]);
                }
            }
        } catch (IOException e) {
            return out;
        }
        return out;
    }
}
