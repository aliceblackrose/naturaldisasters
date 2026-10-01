package io.github.aliceblackrose.naturaldisasters;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/** Applies the physical effects for each disaster without owning round state. */
final class DisasterEffects {
  private static final int TICKS_PER_SECOND = 20;

  private final NaturalDisastersPlugin plugin;
  private final Map<World, FloodRange> floodRanges = new HashMap<>();
  private final Map<World, Location> tornadoCenters = new HashMap<>();

  DisasterEffects(NaturalDisastersPlugin plugin) {
    this.plugin = plugin;
  }

  void prepare(DisasterType disaster, List<Player> players) {
    clear();
    if (disaster == DisasterType.FLASH_FLOOD) {
      prepareFlood(players);
    } else if (disaster == DisasterType.TORNADO) {
      prepareTornado(players);
    }
  }

  void tick(
      DisasterType disaster, List<Player> players, int elapsedTicks, int totalDurationTicks) {
    switch (disaster) {
      case EARTHQUAKE -> tickEarthquake(players, elapsedTicks);
      case ACID_RAIN -> tickAcidRain(players, elapsedTicks);
      case LIGHTNING_STORM -> tickLightningStorm(players, elapsedTicks);
      case METEOR_SHOWER -> tickMeteorShower(players, elapsedTicks);
      case FLASH_FLOOD -> tickFlashFlood(players, elapsedTicks, totalDurationTicks);
      case TORNADO -> tickTornado(players, elapsedTicks);
    }
  }

  void clear() {
    floodRanges.clear();
    tornadoCenters.clear();
  }

  private void tickEarthquake(List<Player> players, int elapsedTicks) {
    if (elapsedTicks % 10 != 0) {
      return;
    }
    double strength = plugin.getConfig().getDouble("disasters.earthquake.knockback", 0.35);
    double damage = plugin.getConfig().getDouble("disasters.earthquake.damage", 1.0);
    ThreadLocalRandom random = ThreadLocalRandom.current();
    for (Player player : players) {
      player.setVelocity(player.getVelocity().add(randomShake(random, strength)));
      if (elapsedTicks % TICKS_PER_SECOND == 0 && damage > 0.0) {
        player.damage(damage);
      }
    }
  }

  private static Vector randomShake(ThreadLocalRandom random, double strength) {
    return new Vector(
        random.nextDouble(-strength, strength),
        random.nextDouble(0.05, 0.18),
        random.nextDouble(-strength, strength));
  }

  private void tickAcidRain(List<Player> players, int elapsedTicks) {
    if (elapsedTicks % TICKS_PER_SECOND != 0) {
      return;
    }
    double damage = plugin.getConfig().getDouble("disasters.acid-rain.damage", 2.0);
    for (Player player : players) {
      if (isExposedToSky(player)) {
        player.damage(damage);
        player.sendActionBar(Component.text("ACID RAIN - FIND COVER", NamedTextColor.GREEN));
      } else {
        player.sendActionBar(Component.text("Sheltered from acid rain", NamedTextColor.GRAY));
      }
    }
  }

  private void tickLightningStorm(List<Player> players, int elapsedTicks) {
    int interval =
        secondsToTicks(
            Math.max(
                1,
                plugin
                    .getConfig()
                    .getInt("disasters.lightning-storm.interval-seconds", 2)));
    if (elapsedTicks % interval != 0) {
      return;
    }

    List<Player> exposed = players.stream().filter(this::isExposedToSky).toList();
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

  private void tickMeteorShower(List<Player> players, int elapsedTicks) {
    int interval =
        secondsToTicks(
            Math.max(
                1,
                plugin.getConfig().getInt("disasters.meteor-shower.interval-seconds", 2)));
    if (elapsedTicks % interval != 0 || players.isEmpty()) {
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
      floodRanges.put(entry.getKey(), floodRange(entry.getValue(), rise));
    }
  }

  private static FloodRange floodRange(List<Player> players, int rise) {
    int minimum =
        players.stream().mapToInt(player -> player.getLocation().getBlockY()).min().orElse(0);
    int maximum =
        players.stream().mapToInt(player -> player.getLocation().getBlockY()).max().orElse(0);
    return new FloodRange(minimum - 4, Math.max(minimum + rise, maximum + 8));
  }

  private void tickFlashFlood(
      List<Player> players, int elapsedTicks, int totalDurationTicks) {
    if (elapsedTicks % TICKS_PER_SECOND != 0) {
      return;
    }
    double damage = plugin.getConfig().getDouble("disasters.flash-flood.damage", 3.0);
    for (Player player : players) {
      FloodRange range = floodRanges.get(player.getWorld());
      if (range == null) {
        continue;
      }
      int level =
          DisasterMath.interpolate(
              range.startY(), range.targetY(), elapsedTicks, totalDurationTicks);
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

  private void tickTornado(List<Player> players, int elapsedTicks) {
    if (elapsedTicks % TICKS_PER_SECOND == 0) {
      driftTornadoCenters();
    }
    double radius =
        Math.max(4.0, plugin.getConfig().getDouble("disasters.tornado.radius", 24.0));
    double strength = plugin.getConfig().getDouble("disasters.tornado.pull-strength", 0.45);
    double lift = plugin.getConfig().getDouble("disasters.tornado.lift", 0.25);
    double damage = plugin.getConfig().getDouble("disasters.tornado.damage", 1.0);
    for (Player player : players) {
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
      if (distanceSquared < 16.0 && elapsedTicks % TICKS_PER_SECOND == 0) {
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

  private boolean isExposedToSky(Player player) {
    Location location = player.getLocation();
    int highest =
        player.getWorld().getHighestBlockYAt(location.getBlockX(), location.getBlockZ());
    return location.getBlockY() > highest;
  }

  private static int secondsToTicks(int seconds) {
    return Math.max(1, seconds) * TICKS_PER_SECOND;
  }

  private record FloodRange(int startY, int targetY) {}
}
