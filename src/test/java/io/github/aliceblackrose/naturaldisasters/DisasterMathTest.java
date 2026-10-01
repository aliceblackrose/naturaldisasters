package io.github.aliceblackrose.naturaldisasters;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DisasterMathTest {
  @Test
  void interpolateStartsAtStartValue() {
    assertEquals(10, DisasterMath.interpolate(10, 30, 0, 100));
  }

  @Test
  void interpolateReachesTargetAtDuration() {
    assertEquals(30, DisasterMath.interpolate(10, 30, 100, 100));
  }

  @Test
  void interpolateClampsPastDuration() {
    assertEquals(30, DisasterMath.interpolate(10, 30, 200, 100));
  }

  @Test
  void interpolateHandlesInvalidDuration() {
    assertEquals(30, DisasterMath.interpolate(10, 30, 10, 0));
  }
}
