package io.github.aliceblackrose.naturaldisasters;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Disasters that can be selected for a survival round. */
public enum DisasterType {
  EARTHQUAKE(
      "earthquake",
      "Earthquake",
      "Keep moving and avoid high ledges while the ground throws you around."),
  ACID_RAIN(
      "acid-rain", "Acid Rain", "Get under a solid roof. Players exposed to the sky take damage."),
  LIGHTNING_STORM(
      "lightning-storm",
      "Lightning Storm",
      "Find cover. Lightning repeatedly targets exposed survivors."),
  METEOR_SHOWER(
      "meteor-shower",
      "Meteor Shower",
      "Spread out and keep moving. Meteors explode near random survivors."),
  FLASH_FLOOD(
      "flash-flood",
      "Flash Flood",
      "Climb to high ground before the virtual flood line reaches you."),
  TORNADO(
      "tornado",
      "Tornado",
      "Stay away from the funnel. It pulls nearby survivors toward its center.");

  private final String configKey;
  private final String title;
  private final String tip;

  DisasterType(String configKey, String title, String tip) {
    this.configKey = configKey;
    this.title = title;
    this.tip = tip;
  }

  /** Configuration and command key for this disaster. */
  public String key() {
    return configKey;
  }

  /** Human-readable disaster name. */
  public String displayName() {
    return title;
  }

  /** Short survival hint shown at the beginning of a round. */
  public String survivalTip() {
    return tip;
  }

  /** Resolves a command/config key to a disaster type. */
  public static Optional<DisasterType> fromKey(String value) {
    String normalized = value.toLowerCase(Locale.ROOT).replace('_', '-');
    return Arrays.stream(values()).filter(type -> type.configKey.equals(normalized)).findFirst();
  }
}
