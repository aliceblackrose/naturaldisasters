package io.github.aliceblackrose.naturaldisasters;

/** Small deterministic calculations shared by disaster effects. */
public final class DisasterMath {
  private DisasterMath() {}

  /** Linearly interpolates an integer hazard level over a bounded elapsed duration. */
  public static int interpolate(int start, int target, int elapsedTicks, int durationTicks) {
    if (durationTicks <= 0) {
      return target;
    }
    double progress = Math.clamp((double) elapsedTicks / durationTicks, 0.0, 1.0);
    return (int) Math.floor(start + (target - start) * progress);
  }
}
