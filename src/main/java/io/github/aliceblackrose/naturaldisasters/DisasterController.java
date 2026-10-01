package io.github.aliceblackrose.naturaldisasters;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/** Owns the survival-round state machine and delegates hazard effects. */
public final class DisasterController {
  private static final int TICKS_PER_SECOND = 20;
  private static final int STEP_TICKS = 5;

  private final NaturalDisastersPlugin plugin;
  private final DisasterEffects effects;
  private final Set<UUID> participants = new HashSet<>();
  private final Set<UUID> eliminated = new HashSet<>();

  private Optional<BukkitTask> task = Optional.empty();
  private Phase phase = Phase.IDLE;
  private Optional<DisasterType> queuedDisaster = Optional.empty();
  private Optional<DisasterType> activeDisaster = Optional.empty();
  private int phaseTicks;
  private int activeElapsedTicks;

  /** Creates a controller bound to the owning plugin. */
  public DisasterController(NaturalDisastersPlugin plugin) {
    this.plugin = plugin;
    effects = new DisasterEffects(plugin);
  }

  /** Starts the repeating automatic round loop if it is enabled in configuration. */
  public void startLoop() {
    if (task.isPresent()) {
      return;
    }
    resetToIntermission();
    task =
        Optional.of(
            plugin
                .getServer()
                .getScheduler()
                .runTaskTimer(plugin, this::tick, STEP_TICKS, STEP_TICKS));
  }

  /** Cancels all scheduled work and clears the current round. */
  public void shutdown() {
    task.ifPresent(BukkitTask::cancel);
    task = Optional.empty();
    clearRoundState();
    phase = Phase.IDLE;
    phaseTicks = 0;
  }

  /** Starts a selected disaster immediately, interrupting any countdown or active round. */
  public void startImmediate(DisasterType disaster) {
    endActiveEffects();
    queuedDisaster = Optional.empty();
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
          "warning for " + displayName(queuedDisaster) + " (" + seconds + "s)";
      case ACTIVE ->
          displayName(activeDisaster)
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
    queuedDisaster = selected;
    DisasterType disaster = selected.orElseThrow();
    phase = Phase.WARNING;
    phaseTicks = secondsToTicks(plugin.getConfig().getInt("rounds.warning-seconds", 10));
    broadcast("Next disaster: " + disaster.displayName(), NamedTextColor.RED);
    broadcast(disaster.survivalTip(), NamedTextColor.YELLOW);
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

    Optional<DisasterType> selected = queuedDisaster;
    queuedDisaster = Optional.empty();
    if (selected.isEmpty()) {
      resetToIntermission();
      return;
    }
    beginDisaster(selected.orElseThrow());
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

    activeDisaster = Optional.of(disaster);
    activeElapsedTicks = 0;
    phase = Phase.ACTIVE;
    phaseTicks = secondsToTicks(plugin.getConfig().getInt("rounds.duration-seconds", 90));
    effects.prepare(disaster, players);
    broadcast(disaster.displayName() + " has started!", NamedTextColor.RED);
    broadcast(disaster.survivalTip(), NamedTextColor.YELLOW);
  }

  private void tickActive() {
    if (activeDisaster.isEmpty()) {
      resetToIntermission();
      return;
    }

    DisasterType disaster = activeDisaster.orElseThrow();
    effects.tick(disaster, activePlayers(), activeElapsedTicks, activeElapsedTicks + phaseTicks);
    activeElapsedTicks += STEP_TICKS;
    phaseTicks -= STEP_TICKS;
    if (phaseTicks > 0) {
      return;
    }

    announceRoundEnd();
    endActiveEffects();
    resetToIntermission();
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
    activeDisaster = Optional.empty();
    effects.clear();
  }

  private void clearRoundState() {
    queuedDisaster = Optional.empty();
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

  private static String displayName(Optional<DisasterType> disaster) {
    return disaster.map(DisasterType::displayName).orElse("unknown disaster");
  }

  private enum Phase {
    IDLE,
    INTERMISSION,
    WARNING,
    ACTIVE
  }
}
