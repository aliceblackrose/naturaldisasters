package io.github.aliceblackrose.naturaldisasters;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/** Owns the round loop and applies all disaster effects on the server thread. */
public final class DisasterController {
  private static final int TICKS_PER_SECOND = 20;
  private static final int STEP_TICKS = 5;

  private final NaturalDisastersPlugin plugin;
  private final Set<UUID> participants = new HashSet<>();
  private final Set<UUID> eliminated = new HashSet<>();
  private final Map<World, FloodRange> floodRanges = new HashMap<>();
  private final Map<World, Location> tornadoCenters = new HashMap<>();

  private BukkitTask task;
  private Phase phase = Phase.IDLE;
  private DisasterType queuedDisaster;
  private DisasterType activeDisaster;
  private int phaseTicks;
  private int activeElapsedTicks;

  /** Creates a controller bound to the owning plugin. */
  public DisasterController(NaturalDisastersPlugin plugin) {
    this.plugin = plugin;
  }

  /** Starts the repeating automatic round loop if it is enabled in configuration. */
  public void startLoop() {
    if (task != null) {
      return;
    }
    resetToIntermission();
    task =
        plugin
            .getServer()
            .getScheduler()
            .runTaskTimer(plugin, this::tick, STEP_TICKS, STEP_TICKS);
  }

  /** Cancels all scheduled work and clears the current round. */
  public void shutdown() {
    if (task != null) {
      task.cancel();
      task = null;
    }
    clearRoundState();
    phase = Phase.IDLE;
    phaseTicks = 0;
  }

  /** Starts a selected disaster immediately, interrupting any countdown or active round. */
  public void startImmediate(DisasterType disaster) {
    endActiveEffects();
    queuedDisaster = null;
    beginDisaster(disaster);
  }

  /** Stops the active round and returns to the configured intermission. */
  public boolean stopCurrentRound() {
    if (phase != Phase.ACTIVE && phase != Phase.WARNING) {
      return false;
    }
    if (phase == Phase.ACTIVE) {
      announceRoundEnd();
    }
    endActiveEffects();
    resetToIntermission();
    return true;
  }

  /** Marks a participant as eliminated after a player death. */
  public void markEliminated(UUID playerId) {
    if (phase == Phase.ACTIVE && participants.contains(playerId)) {
      eliminated.add(playerId);
    }
  }

  /** Returns a compact description of the current controller state. */
  public String status() {
    int seconds = Math.max(0, (phaseTicks + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND);
    return switch (phase) {
      case IDLE -> "idle";
      case INTERMISSION -> "intermission (" + seconds + "s)";
      case WARNING ->
          "warning for " + safeDisplayName(queuedDisaster) + " (" + seconds + "s)";
      case ACTIVE ->
          safeDisplayName(activeDisaster)
              + " active ("
              + seconds
              + "s remaining, "
              + survivorCount()
              + " survivors)";
    };
  }

  /** Returns all disaster types currently enabled for automatic selection. */
  public List<DisasterType> enabledDisasters() {
    return Arrays.stream(DisasterType.values()).filter(this::isEnabled).toList();
  }

  private void tick() {
    switch (phase) {
      case IDLE -> resetToIntermission();
      case INTERMISSION -> tickIntermission();
      case WARNING -> tickWarning();
      case ACTIVE -> tickActive();
    }
  }

  private void tickIntermission() {
    if (!plugin.getConfig().getBoolean("rounds.enabled", true)) {
      return;
    }
    phaseTicks -= STEP_TICKS;
    if (phaseTicks > 0) {
      return;
    }

    if (eligiblePlayers().size() < minimumPlayers()) {
      phaseTicks = secondsToTicks(5);
      return;
    }

    Optional<DisasterType> selected = chooseDisaster();
    if (selected.isEmpty()) {
      phaseTicks = secondsToTicks(10);
      return;
    }
    queuedDisaster = selected.orElseThrow();
    phase = Phase.WARNING;
    phaseTicks = secondsToTicks(plugin.getConfig().getInt("rounds.warning-seconds", 10));
    broadcast("Next disaster: " + queuedDisaster.displayName(), NamedTextColor.RED);
    broadcast(queuedDisaster.survivalTip(), NamedTextColor.YELLOW);
  }

  private void tickWarning() {
    phaseTicks -= STEP_TICKS;
    int seconds = Math.max(0, (phaseTicks + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND);
    if (phaseTicks > 0) {
      if (phaseTicks % TICKS_PER_SECOND == 0 && (seconds <= 5 || seconds % 10 == 0)) {
        sendActionBar("Disaster begins in " + seconds + "s", NamedTextColor.GOLD);
      }
      return;
    }

    DisasterType disaster = queuedDisaster;
    queuedDisaster = null;
    if (disaster == null) {
      resetToIntermission();
      return;
    }
    beginDisaster(disaster);
  }

  private void beginDisaster(DisasterType disaster) {
    List<Player> players = eligiblePlayers();
    if (players.isEmpty()) {
      resetToIntermission();
      return;
    }

    participants.clear();
    eliminated.clear();
    for (Player player : players) {
      participants.add(player.getUniqueId());
    }

    activeDisaster = disaster;
    activeElapsedTicks = 0;
    phase = Phase.ACTIVE;
    phaseTicks = secondsToTicks(plugin.getConfig().getInt("rounds.duration-seconds", 90));
    prepareDisaster(disaster, players);

    broadcast(disaster.displayName() + " has started!", NamedTextColor.RED);
    broadcast(disaster.survivalTip(), NamedTextColor.YELLOW);
  }

  private void tickActive() {
    DisasterType disaster = activeDisaster;
    if (disaster == null) {
      resetToIntermission();
      return;
    }

    applyDisaster(disaster);
    activeElapsedTicks += STEP_TICKS;
    phaseTicks -= STEP_TICKS;
    if (phaseTicks > 0) {
      return;
    }

    announceRoundEnd();
    endActiveEffects();
    resetToIntermission();
  }

  private void prepareDisaster(DisasterType disaster, List<Player> players) {
    floodRanges.clear();
    tornadoCenters.clear();
    if (disaster == DisasterType.FLASH_FLOOD) {
      prepareFlood(players);
    } else if (disaster == DisasterType.TORNADO) {
      prepareTornado(players);
    }
  }

  private void applyDisaster(DisasterType disaster) {
    switch (disaster) {
      case EARTHQUAKE -> tickEarthquake();
      case ACID_RAIN -> tickAcidRain();
      case LIGHTNING_STORM -> tickLightningStorm();
      case METEOR_SHOWER -> tickMeteorShower();
      case FLASH_FLOOD -> tickFlashFlood();
      case TORNADO -> tickTornado();
    }
  }

  private void tickEarthquake() {
    if (activeElapsedTicks % 10 != 0) {
      return;
    }
    double strength = plugin.getConfig().getDouble("disasters.earthquake.knockback", 0.35);
    double damage = plugin.getConfig().getDouble("disasters.earthquake.damage", 1.0);
    ThreadLocalRandom random = ThreadLocalRandom.current();
    for (Player player : activePlayers()) {
      Vector shake =
          new Vector(
              random.nextDouble(-strength, strength),
              random.nextDouble(0.05, 0.18),
              random.nextDouble(-strength, strength));
      player.setVelocity(player.getVelocity().add(shake));
      if (activeElapsedTicks % TICKS_PER_SECOND == 0 && damage > 0.0) {
        player.damage(damage);
      }
    }
  }

  private void tickAcidRain() {
    if (activeElapsedTicks % TICKS_PER_SECOND != 0) {
      return;
    }
    double damage = plugin.getConfig().getDouble("disasters.acid-rain.damage", 2.0);
    for (Player player : activePlayers()) {
      if (isExposedToSky(player)) {
        player.damage(damage);
        player.sendActionBar(Component.text("ACID RAIN - FIND COVER", NamedTextColor.GREEN));
      } else {
        player.sendActionBar(Component.text("Sheltered from acid rain", NamedTextColor.GRAY));
      }
    }
  }

  private void tickLightningStorm() {
    int interval =
        secondsToTicks(
            Math.max(
                1,
                plugin
                    .getConfig()
                    .getInt("disasters.lightning-storm.interval-seconds", 2)));
    if (activeElapsedTicks % interval != 0) {
      return;
    }

    List<Player> exposed = activePlayers().stream().filter(this::isExposedToSky).toList();
    if (exposed.isEmpty()) {
      return;
    }
    Player target = exposed.get(ThreadLocalRandom.current().nextInt(exposed.size()));
    boolean destructive = plugin.getConfig().getBoolean("disasters.block-damage", false);
    if (destructive) {
      target.getWorld().strikeLightning(target.getLocation());
    } else {
      target.getWorld().strikeLightningEffect(target.getLocation());
      target.damage(plugin.getConfig().getDouble("disasters.lightning-storm.damage", 5.0));
    }
  }

  private void tickMeteorShower() {
    int interval =
        secondsToTicks(
            Math.max(
                1,
                plugin.getConfig().getInt("disasters.meteor-shower.interval-seconds", 2)));
    if (activeElapsedTicks % interval != 0) {
      return;
    }

    List<Player> players = activePlayers();
    if (players.isEmpty()) {
      return;
    }
    Player target = players.get(ThreadLocalRandom.current().nextInt(players.size()));
    int radius = Math.max(1, plugin.getConfig().getInt("disasters.meteor-shower.radius", 8));
    ThreadLocalRandom random = ThreadLocalRandom.current();
    int x = target.getLocation().getBlockX() + random.nextInt(-radius, radius + 1);
    int z = target.getLocation().getBlockZ() + random.nextInt(-radius, radius + 1);
    World world = target.getWorld();
    int y = world.getHighestBlockYAt(x, z) + 1;
    Location impact = new Location(world, x + 0.5, y, z + 0.5);
    float power = (float) plugin.getConfig().getDouble("disasters.meteor-shower.power", 3.0);
    boolean breakBlocks = plugin.getConfig().getBoolean("disasters.block-damage", false);
    world.createExplosion(impact, power, false, breakBlocks, null);
  }

  private void prepareFlood(List<Player> players) {
    Map<World, List<Player>> byWorld =
        players.stream().collect(Collectors.groupingBy(Player::getWorld));
    int rise =
        Math.max(8, plugin.getConfig().getInt("disasters.flash-flood.rise-blocks", 18));
    for (Map.Entry<World, List<Player>> entry : byWorld.entrySet()) {
      int minimum =
          entry.getValue().stream()
              .mapToInt(player -> player.getLocation().getBlockY())
              .min()
              .orElse(0);
      int maximum =
          entry.getValue().stream()
              .mapToInt(player -> player.getLocation().getBlockY())
              .max()
              .orElse(0);
      floodRanges.put(
          entry.getKey(),
          new FloodRange(minimum - 4, Math.max(minimum + rise, maximum + 8)));
    }
  }

  private void tickFlashFlood() {
    if (activeElapsedTicks % TICKS_PER_SECOND != 0) {
      return;
    }
    int duration = secondsToTicks(plugin.getConfig().getInt("rounds.duration-seconds", 90));
    double damage = plugin.getConfig().getDouble("disasters.flash-flood.damage", 3.0);
    for (Player player : activePlayers()) {
      FloodRange range = floodRanges.get(player.getWorld());
      if (range == null) {
        continue;
      }
      int level =
          DisasterMath.interpolate(range.startY(), range.targetY(), activeElapsedTicks, duration);
      player.sendActionBar(Component.text("Flood level: Y " + level, NamedTextColor.AQUA));
      if (player.getLocation().getY() <= level) {
        player.damage(damage);
      }
    }
  }

  private void prepareTornado(List<Player> players) {
    Map<World, List<Player>> byWorld =
        players.stream().collect(Collectors.groupingBy(Player::getWorld));
    for (Map.Entry<World, List<Player>> entry : byWorld.entrySet()) {
      List<Player> worldPlayers = entry.getValue();
      Player seed = worldPlayers.get(ThreadLocalRandom.current().nextInt(worldPlayers.size()));
      tornadoCenters.put(entry.getKey(), seed.getLocation().clone());
    }
  }

  private void tickTornado() {
    if (activeElapsedTicks % TICKS_PER_SECOND == 0) {
      driftTornadoCenters();
    }
    double radius =
        Math.max(4.0, plugin.getConfig().getDouble("disasters.tornado.radius", 24.0));
    double strength = plugin.getConfig().getDouble("disasters.tornado.pull-strength", 0.45);
    double lift = plugin.getConfig().getDouble("disasters.tornado.lift", 0.25);
    double damage = plugin.getConfig().getDouble("disasters.tornado.damage", 1.0);
    for (Player player : activePlayers()) {
      Location center = tornadoCenters.get(player.getWorld());
      if (center == null) {
        continue;
      }
      Vector delta = center.toVector().subtract(player.getLocation().toVector());
      delta.setY(0.0);
      double distanceSquared = delta.lengthSquared();
      if (distanceSquared > radius * radius || distanceSquared < 0.01) {
        continue;
      }
      Vector pull = delta.normalize().multiply(strength).setY(lift);
      player.setVelocity(player.getVelocity().multiply(0.75).add(pull));
      if (distanceSquared < 16.0 && activeElapsedTicks % TICKS_PER_SECOND == 0) {
        player.damage(damage);
      }
    }
  }

  private void driftTornadoCenters() {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    for (Map.Entry<World, Location> entry : tornadoCenters.entrySet()) {
      Location center = entry.getValue();
      center.add(random.nextDouble(-3.0, 3.0), 0.0, random.nextDouble(-3.0, 3.0));
      center.setY(entry.getKey().getHighestBlockYAt(center) + 1.0);
    }
  }

  private void announceRoundEnd() {
    int survivors = survivorCount();
    int total = participants.size();
    broadcast(
        "Round over! " + survivors + "/" + total + " participants survived.",
        NamedTextColor.GREEN);
  }

  private int survivorCount() {
    int count = 0;
    for (UUID playerId : participants) {
      Player player = plugin.getServer().getPlayer(playerId);
      if (player != null
          && player.isOnline()
          && !player.isDead()
          && !eliminated.contains(playerId)) {
        count++;
      }
    }
    return count;
  }

  private List<Player> activePlayers() {
    List<Player> players = new ArrayList<>();
    for (UUID playerId : participants) {
      Player player = plugin.getServer().getPlayer(playerId);
      if (player == null
          || !player.isOnline()
          || player.isDead()
          || eliminated.contains(playerId)) {
        continue;
      }
      players.add(player);
    }
    return players;
  }

  private List<Player> eligiblePlayers() {
    List<String> configuredWorlds = plugin.getConfig().getStringList("rounds.worlds");
    List<Player> players = new ArrayList<>();
    for (Player player : plugin.getServer().getOnlinePlayers()) {
      boolean worldAllowed =
          configuredWorlds.isEmpty() || configuredWorlds.contains(player.getWorld().getName());
      if (isEligibleGameMode(player.getGameMode()) && worldAllowed && !player.isDead()) {
        players.add(player);
      }
    }
    return List.copyOf(players);
  }

  private static boolean isEligibleGameMode(GameMode gameMode) {
    return gameMode == GameMode.SURVIVAL || gameMode == GameMode.ADVENTURE;
  }

  private boolean isExposedToSky(Player player) {
    Location location = player.getLocation();
    int highest =
        player.getWorld().getHighestBlockYAt(location.getBlockX(), location.getBlockZ());
    return location.getBlockY() > highest;
  }

  private Optional<DisasterType> chooseDisaster() {
    List<DisasterType> enabled = enabledDisasters();
    if (enabled.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(enabled.get(ThreadLocalRandom.current().nextInt(enabled.size())));
  }

  private boolean isEnabled(DisasterType disaster) {
    return plugin.getConfig().getBoolean("disasters." + disaster.key() + ".enabled", true);
  }

  private int minimumPlayers() {
    return Math.max(1, plugin.getConfig().getInt("rounds.minimum-players", 1));
  }

  private void resetToIntermission() {
    clearRoundState();
    phase = Phase.INTERMISSION;
    phaseTicks = secondsToTicks(plugin.getConfig().getInt("rounds.intermission-seconds", 30));
  }

  private void endActiveEffects() {
    activeDisaster = null;
    floodRanges.clear();
    tornadoCenters.clear();
  }

  private void clearRoundState() {
    queuedDisaster = null;
    endActiveEffects();
    participants.clear();
    eliminated.clear();
    activeElapsedTicks = 0;
  }

  private void broadcast(String message, NamedTextColor color) {
    plugin
        .getServer()
        .broadcast(
            Component.text("[Natural Disasters] ", NamedTextColor.DARK_RED)
                .append(Component.text(message, color)));
  }

  private void sendActionBar(String message, NamedTextColor color) {
    Component component = Component.text(message, color);
    for (Player player : eligiblePlayers()) {
      player.sendActionBar(component);
    }
  }

  private static int secondsToTicks(int seconds) {
    return Math.max(1, seconds) * TICKS_PER_SECOND;
  }

  private static String safeDisplayName(DisasterType disaster) {
    return disaster == null ? "unknown disaster" : disaster.displayName();
  }

  private enum Phase {
    IDLE,
    INTERMISSION,
    WARNING,
    ACTIVE
  }

  private record FloodRange(int startY, int targetY) {}
}
