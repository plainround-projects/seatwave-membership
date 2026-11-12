package io.plainround.seatwave.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * The phi accrual detector against an independent, brute-force computation of phi from the same
 * window, and its window's eviction of old intervals.
 */
class PhiAccrualDetectorTest {
  private static final int WINDOW = 10;
  private static final long MIN_STD_DEVIATION_MICROS = 100_000;
  private static final long ACCEPTABLE_PAUSE_MICROS = 1_000_000;
  private static final long FIRST_ESTIMATE_MICROS = 1_000_000;
  private static final double THRESHOLD = 8.0;
  private static final long SEED = 2_404;
  private static final int HEARTBEATS = 50;
  // Intervals of 1 s with up to 400 ms of jitter, so the deviation is above the 100 ms floor.
  private static final long BASE_INTERVAL_MICROS = 1_000_000;
  private static final long JITTER_MICROS = 400_000;
  private static final long SCAN_STEP_MICROS = 100;
  private static final long SCAN_LIMIT_MICROS = 20_000_000;
  private static final long SLOW_INTERVAL_MICROS = 3_000_000;
  // Pekko's logistic approximation, restated here so the brute force does not call the detector.
  private static final double LOGISTIC_LINEAR = 1.5976;
  private static final double LOGISTIC_CUBIC = 0.070566;

  @Test
  void suspectsAtTheSilenceWhereABruteForcePhiFirstReachesTheThreshold() {
    PhiAccrualDetector detector = detector();
    long[] arrivals = jitteredArrivals();
    Arrays.stream(arrivals).forEach(detector::recordHeartbeat);
    double[] window = lastIntervals(arrivals);

    long bruteForceCrossing = 0;
    while (bruteForcePhi(window, bruteForceCrossing) < THRESHOLD) {
      bruteForceCrossing += SCAN_STEP_MICROS;
    }

    assertThat(bruteForceCrossing).isLessThan(SCAN_LIMIT_MICROS);
    assertThat(detector.suspectAfterMicros())
        .isCloseTo(bruteForceCrossing, within(SCAN_STEP_MICROS));
  }

  @Test
  void reportsTheSamePhiAsTheBruteForceAtEverySilence() {
    PhiAccrualDetector detector = detector();
    long[] arrivals = jitteredArrivals();
    Arrays.stream(arrivals).forEach(detector::recordHeartbeat);
    double[] window = lastIntervals(arrivals);
    long last = arrivals[arrivals.length - 1];

    for (long silence = 0; silence <= SLOW_INTERVAL_MICROS; silence += JITTER_MICROS) {
      assertThat(detector.suspicionLevel(last + silence))
          .isCloseTo(bruteForcePhi(window, silence), within(1e-9));
    }
  }

  @Test
  void forgetsIntervalsThatLeftTheWindow() {
    PhiAccrualDetector slowThenFast = detector();
    PhiAccrualDetector onlyFast = detector();
    long arrival = 0;
    for (int heartbeat = 0; heartbeat <= WINDOW; heartbeat++) {
      slowThenFast.recordHeartbeat(arrival);
      arrival += SLOW_INTERVAL_MICROS;
    }
    arrival -= SLOW_INTERVAL_MICROS;
    for (int heartbeat = 0; heartbeat < WINDOW; heartbeat++) {
      arrival += BASE_INTERVAL_MICROS;
      slowThenFast.recordHeartbeat(arrival);
    }
    for (int heartbeat = 0; heartbeat <= WINDOW + 2; heartbeat++) {
      onlyFast.recordHeartbeat(heartbeat * BASE_INTERVAL_MICROS);
    }

    assertThat(slowThenFast.suspectAfterMicros())
        .isCloseTo(onlyFast.suspectAfterMicros(), within(1L));
  }

  @Test
  void suspectsWhenTheSilenceExceedsItsEffectiveTimeout() {
    PhiAccrualDetector detector = detector();
    detector.recordHeartbeat(0);

    assertThat(detector.isSuspected(detector.suspectAfterMicros() + 1)).isTrue();
    assertThat(detector.isSuspected(detector.suspectAfterMicros() - 1)).isFalse();
    assertThat(detector.fresh().lastHeartbeatMicros()).isZero();
    assertThat(detector.label()).isEqualTo("phi 8, pause 1 s");
  }

  @Test
  void rejectsArrivalsThatGoBackwardsAndInvalidSettings() {
    PhiAccrualDetector detector = detector();
    detector.recordHeartbeat(FIRST_ESTIMATE_MICROS);

    assertThatThrownBy(() -> detector.recordHeartbeat(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("backwards");
    assertThatThrownBy(() -> new PhiAccrualDetector.Settings(0.5, WINDOW, 1, 0, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("threshold");
    assertThatThrownBy(() -> new PhiAccrualDetector.Settings(THRESHOLD, 1, 1, 0, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("windowSize");
    assertThatThrownBy(() -> new PhiAccrualDetector.Settings(THRESHOLD, WINDOW, 1, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("firstHeartbeatEstimateMicros");
  }

  private static PhiAccrualDetector detector() {
    return new PhiAccrualDetector(
        new PhiAccrualDetector.Settings(
            THRESHOLD,
            WINDOW,
            MIN_STD_DEVIATION_MICROS,
            ACCEPTABLE_PAUSE_MICROS,
            FIRST_ESTIMATE_MICROS));
  }

  private static long[] jitteredArrivals() {
    SplittableRandom random = new SplittableRandom(SEED);
    long[] arrivals = new long[HEARTBEATS];
    for (int heartbeat = 1; heartbeat < HEARTBEATS; heartbeat++) {
      arrivals[heartbeat] =
          arrivals[heartbeat - 1] + BASE_INTERVAL_MICROS + random.nextLong(JITTER_MICROS);
    }
    return arrivals;
  }

  private static double[] lastIntervals(long[] arrivals) {
    double[] window = new double[WINDOW];
    for (int slot = 0; slot < WINDOW; slot++) {
      int index = arrivals.length - WINDOW + slot;
      window[slot] = arrivals[index] - arrivals[index - 1];
    }
    return window;
  }

  private static double bruteForcePhi(double[] window, long silenceMicros) {
    double mean = Arrays.stream(window).average().orElseThrow();
    double variance =
        Arrays.stream(window).map(interval -> (interval - mean) * (interval - mean)).sum()
            / window.length;
    double deviation = Math.max(Math.sqrt(variance), MIN_STD_DEVIATION_MICROS);
    double standardized = (silenceMicros - mean - ACCEPTABLE_PAUSE_MICROS) / deviation;
    double exponential =
        Math.exp(-standardized * (LOGISTIC_LINEAR + LOGISTIC_CUBIC * standardized * standardized));
    return standardized > 0
        ? -Math.log10(exponential / (1.0 + exponential))
        : -Math.log10(1.0 - 1.0 / (1.0 + exponential));
  }
}
