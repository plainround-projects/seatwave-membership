package io.plainround.seatwave.membership;

import java.util.Locale;

/**
 * The phi accrual detector of Hayashibara and colleagues (2004), in the form Apache Pekko uses: it
 * keeps the last intervals between heartbeats, fits a normal distribution to them (mean and
 * standard deviation, the deviation never below a floor), and reports phi = -log10 of the
 * probability that a heartbeat would still arrive after the current silence. Phi 1 means a 10
 * percent chance the node is fine and the heartbeat is late; phi 8 means one in 100 million. An
 * acceptable pause is added to the mean, as Pekko does, so that an expected pause does not raise
 * phi. Not thread-safe; one instance per monitored node.
 */
final class PhiAccrualDetector implements FailureDetector {
  // Pekko's logistic approximation of the normal distribution's tail (its source cites the
  // constants and an error of about 0.00014).
  private static final double LOGISTIC_LINEAR = 1.5976;
  private static final double LOGISTIC_CUBIC = 0.070566;
  // Bounds for the search of the standardized silence at which phi reaches the threshold; phi at
  // 60 standard deviations is far above any threshold a detector uses.
  private static final double SEARCH_LOW = 0.0;
  private static final double SEARCH_HIGH = 60.0;
  private static final int SEARCH_STEPS = 80;
  // Pekko seeds the history with the first-heartbeat estimate plus and minus a quarter of it.
  private static final double SEED_SPREAD = 0.25;
  private static final double MICROS_PER_SECOND = 1_000_000.0;

  /**
   * The detector's settings.
   *
   * @param threshold phi at or above which the node is suspected
   * @param windowSize number of intervals kept
   * @param minStdDeviationMicros floor of the standard deviation
   * @param acceptablePauseMicros silence added to the mean before phi rises
   * @param firstHeartbeatEstimateMicros the interval assumed before any is seen
   */
  record Settings(
      double threshold,
      int windowSize,
      long minStdDeviationMicros,
      long acceptablePauseMicros,
      long firstHeartbeatEstimateMicros) {
    Settings {
      if (!(threshold >= 1.0)) {
        throw new IllegalArgumentException("threshold must be at least 1, was " + threshold);
      }
      if (windowSize < 2 || minStdDeviationMicros <= 0 || acceptablePauseMicros < 0) {
        throw new IllegalArgumentException(
            "windowSize >= 2, minStdDeviationMicros > 0 and acceptablePauseMicros >= 0 required");
      }
      if (firstHeartbeatEstimateMicros <= 0) {
        throw new IllegalArgumentException("firstHeartbeatEstimateMicros must be positive");
      }
    }
  }

  private final Settings settings;
  // The standardized silence (in standard deviations past the mean) at which phi equals the
  // threshold. It depends on the threshold alone, so it is found once, not per heartbeat.
  private final double standardizedSilenceAtThreshold;
  private final double[] intervalsMicros;
  private int intervalCount;
  private int nextSlot;
  private double intervalSum;
  private double intervalSquareSum;
  private long lastHeartbeatMicros = -1;

  PhiAccrualDetector(Settings settings) {
    this.settings = settings;
    this.standardizedSilenceAtThreshold = solveForThreshold(settings.threshold());
    this.intervalsMicros = new double[settings.windowSize()];
    double estimate = settings.firstHeartbeatEstimateMicros();
    addInterval(estimate * (1.0 - SEED_SPREAD));
    addInterval(estimate * (1.0 + SEED_SPREAD));
  }

  @Override
  public void recordHeartbeat(long arrivalMicros) {
    if (lastHeartbeatMicros >= 0) {
      if (arrivalMicros < lastHeartbeatMicros) {
        throw new IllegalArgumentException("heartbeat arrivals must not go backwards");
      }
      addInterval(arrivalMicros - lastHeartbeatMicros);
    }
    lastHeartbeatMicros = arrivalMicros;
  }

  @Override
  public long lastHeartbeatMicros() {
    return Math.max(lastHeartbeatMicros, 0);
  }

  @Override
  public long suspectAfterMicros() {
    return (long) (shiftedMeanMicros() + standardizedSilenceAtThreshold * stdDeviationMicros());
  }

  @Override
  public double suspicionLevel(long nowMicros) {
    double standardized =
        (nowMicros - lastHeartbeatMicros() - shiftedMeanMicros()) / stdDeviationMicros();
    return phiOfStandardized(standardized);
  }

  @Override
  public double threshold() {
    return settings.threshold();
  }

  @Override
  public FailureDetector fresh() {
    return new PhiAccrualDetector(settings);
  }

  @Override
  public String label() {
    return String.format(
        Locale.ROOT,
        "phi %.0f, pause %.0f s",
        settings.threshold(),
        settings.acceptablePauseMicros() / MICROS_PER_SECOND);
  }

  private double shiftedMeanMicros() {
    return intervalSum / intervalCount + settings.acceptablePauseMicros();
  }

  private double stdDeviationMicros() {
    double mean = intervalSum / intervalCount;
    double variance = Math.max(intervalSquareSum / intervalCount - mean * mean, 0.0);
    return Math.max(Math.sqrt(variance), settings.minStdDeviationMicros());
  }

  private void addInterval(double intervalMicros) {
    if (intervalCount == intervalsMicros.length) {
      // The window is full: the oldest interval leaves the sums before the new one enters.
      double oldest = intervalsMicros[nextSlot];
      intervalSum -= oldest;
      intervalSquareSum -= oldest * oldest;
    } else {
      intervalCount++;
    }
    intervalsMicros[nextSlot] = intervalMicros;
    intervalSum += intervalMicros;
    intervalSquareSum += intervalMicros * intervalMicros;
    nextSlot = (nextSlot + 1) % intervalsMicros.length;
  }

  private static double phiOfStandardized(double standardized) {
    double exponential =
        Math.exp(-standardized * (LOGISTIC_LINEAR + LOGISTIC_CUBIC * standardized * standardized));
    if (standardized > 0) {
      return -Math.log10(exponential / (1.0 + exponential));
    }
    return -Math.log10(1.0 - 1.0 / (1.0 + exponential));
  }

  private static double solveForThreshold(double threshold) {
    double low = SEARCH_LOW;
    double high = SEARCH_HIGH;
    // Phi grows with the silence, so halving the interval converges on the crossing point.
    for (int step = 0; step < SEARCH_STEPS; step++) {
      double middle = (low + high) / 2.0;
      if (phiOfStandardized(middle) < threshold) {
        low = middle;
      } else {
        high = middle;
      }
    }
    return high;
  }
}
