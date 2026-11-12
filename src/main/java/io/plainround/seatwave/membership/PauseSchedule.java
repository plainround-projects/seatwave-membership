package io.plainround.seatwave.membership;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * When a SeatWave node's JVM stops every thread for a garbage collection, and for how long. Gaps
 * between pauses are exponential; each pause is short (5 to 50 ms), medium (50 to 500 ms) or long
 * (500 ms to 2 s, the walkthrough's worst case). While paused a node sends nothing, but it is not
 * dead: it resumes and carries on. Immutable and thread-safe; the caller owns the random source.
 *
 * @param meanSecondsBetweenPauses average time from one pause to the next
 * @param mediumShare share of pauses of 50 to 500 ms
 * @param longShare share of pauses of 500 ms to 2 s
 */
record PauseSchedule(double meanSecondsBetweenPauses, double mediumShare, double longShare) {
  private static final long MICROS_PER_SECOND = 1_000_000;
  private static final long SHORT_MIN_MICROS = 5_000;
  private static final long SHORT_MAX_MICROS = 50_000;
  private static final long MEDIUM_MAX_MICROS = 500_000;
  private static final long LONG_MAX_MICROS = 2_000_000;
  // Assumed for a steady SeatWave JVM: a pause every 30 s; 1.9 percent medium, 0.1 percent long.
  private static final double STEADY_MEAN_SECONDS = 30.0;
  private static final double STEADY_MEDIUM_SHARE = 0.019;
  private static final double STEADY_LONG_SHARE = 0.001;
  // Assumed for a node under on-sale load: a pause every 10 s; 5 percent medium, 1 percent long.
  private static final double BUSY_MEAN_SECONDS = 10.0;
  private static final double BUSY_MEDIUM_SHARE = 0.05;
  private static final double BUSY_LONG_SHARE = 0.01;

  /** One pause: nothing in the node runs from its start for its duration. */
  record Pause(long startMicros, long durationMicros) {
    Pause {
      if (startMicros < 0 || durationMicros <= 0) {
        throw new IllegalArgumentException("a pause needs a start >= 0 and a duration > 0");
      }
    }

    long endMicros() {
      return startMicros + durationMicros;
    }
  }

  PauseSchedule {
    if (!(meanSecondsBetweenPauses > 0.0)) {
      throw new IllegalArgumentException("meanSecondsBetweenPauses must be positive");
    }
    if (!(mediumShare >= 0.0 && longShare >= 0.0 && mediumShare + longShare <= 1.0)) {
      throw new IllegalArgumentException("pause shares must be >= 0 and add up to at most 1");
    }
  }

  static PauseSchedule steady() {
    return new PauseSchedule(STEADY_MEAN_SECONDS, STEADY_MEDIUM_SHARE, STEADY_LONG_SHARE);
  }

  static PauseSchedule busy() {
    return new PauseSchedule(BUSY_MEAN_SECONDS, BUSY_MEDIUM_SHARE, BUSY_LONG_SHARE);
  }

  /** Draws every pause that starts before {@code untilMicros}, in time order, never overlapping. */
  List<Pause> draw(long untilMicros, SplittableRandom random) {
    List<Pause> pauses = new ArrayList<>();
    double meanGapMicros = meanSecondsBetweenPauses * MICROS_PER_SECOND;
    long nextStartMicros = (long) (-Math.log(1.0 - random.nextDouble()) * meanGapMicros);
    while (nextStartMicros < untilMicros) {
      Pause pause = new Pause(nextStartMicros, drawDurationMicros(random));
      pauses.add(pause);
      // The next gap is counted from the end of this pause, so pauses never overlap.
      nextStartMicros =
          pause.endMicros() + (long) (-Math.log(1.0 - random.nextDouble()) * meanGapMicros);
    }
    return List.copyOf(pauses);
  }

  private long drawDurationMicros(SplittableRandom random) {
    double draw = random.nextDouble();
    if (draw < longShare) {
      return random.nextLong(MEDIUM_MAX_MICROS, LONG_MAX_MICROS + 1);
    }
    if (draw < longShare + mediumShare) {
      return random.nextLong(SHORT_MAX_MICROS, MEDIUM_MAX_MICROS);
    }
    return random.nextLong(SHORT_MIN_MICROS, SHORT_MAX_MICROS);
  }
}
