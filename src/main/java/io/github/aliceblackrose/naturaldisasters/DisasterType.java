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
      "acid-rain",
      "Acid Rain",
      "Get under a solid roof. Players exposed to the sky take damage."),
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

  private final String key;
  private final String displayName;
  private final String survivalTip;

  DisasterType(String key, String displayName, String survivalTip) {
    this.key = key;
    this.displayName = displayName;
    this.survivalTip = survivalTip;
  }

  /** Configuration and command key for this disaster. */
  public String key() {
    return key;
  }

  /** Human-readable disaster name. */
  public String displayName() {
    return displayName;
  }

  /** Short survival hint shown at the beginning of a round. */
  public String survivalTip() {
    return survivalTip;
  }

  /** Resolves a command/config key to a disaster type. */
  public static Optional<DisasterType> fromKey(String value) {
    String normalized = value.toLowerCase(Locale.ROOT).replace('_', '-');
    return Arrays.stream(values()).filter(type -> type.key.equals(normalized)).findFirst();
  }
}
