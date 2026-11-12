package io.plainround.seatwave.membership;

import java.util.SplittableRandom;

/**
 * The simulated network between two SeatWave nodes: a one-way delay drawn from a three-part mixture
 * and an independent chance that a message is lost. The mixture is the module's assumption for an
 * on-sale evening, chosen so that the 99th percentile of the one-way delay is 200 ms: most messages
 * take 1 to 20 ms, a share takes 20 to 200 ms, and a small share 200 to 1,000 ms. Immutable and
 * thread-safe; the caller owns the random source.
 *
 * @param slowShare share of messages delayed 20 to 200 ms
 * @param verySlowShare share of messages delayed 200 to 1,000 ms
 * @param lossProbability chance that one message never arrives
 */
record NetworkConditions(double slowShare, double verySlowShare, double lossProbability) {
  private static final long FAST_MIN_MICROS = 1_000;
  private static final long FAST_MAX_MICROS = 20_000;
  private static final long SLOW_MAX_MICROS = 200_000;
  private static final long VERY_SLOW_MAX_MICROS = 1_000_000;
  // The walkthrough's network: 9 percent slow and 1 percent very slow puts p99 at 200 ms.
  private static final double ON_SALE_SLOW_SHARE = 0.09;
  private static final double ON_SALE_VERY_SLOW_SHARE = 0.01;
  // An assumed loss of one message in a thousand on a healthy network.
  private static final double ON_SALE_LOSS = 0.001;
  // Assumed for the links of an overloaded node: 25 percent slow and 5 percent very slow.
  private static final double BUSY_SLOW_SHARE = 0.25;
  private static final double BUSY_VERY_SLOW_SHARE = 0.05;

  NetworkConditions {
    requireShare(slowShare, "slowShare");
    requireShare(verySlowShare, "verySlowShare");
    requireShare(lossProbability, "lossProbability");
    if (slowShare + verySlowShare > 1.0) {
      throw new IllegalArgumentException("slowShare + verySlowShare must not exceed 1");
    }
  }

  /** The on-sale network of the walkthrough: p99 one-way delay 200 ms, 0.1 percent loss. */
  static NetworkConditions onSale() {
    return new NetworkConditions(ON_SALE_SLOW_SHARE, ON_SALE_VERY_SLOW_SHARE, ON_SALE_LOSS);
  }

  /** The links of an overloaded node: one message in twenty takes 200 ms to 1 s. */
  static NetworkConditions busy() {
    return new NetworkConditions(BUSY_SLOW_SHARE, BUSY_VERY_SLOW_SHARE, ON_SALE_LOSS);
  }

  /** The same delays with another loss probability. */
  NetworkConditions withLoss(double otherLossProbability) {
    return new NetworkConditions(slowShare, verySlowShare, otherLossProbability);
  }

  /** Draws one one-way delay in microseconds. */
  long sampleDelayMicros(SplittableRandom random) {
    double draw = random.nextDouble();
    if (draw < verySlowShare) {
      return random.nextLong(SLOW_MAX_MICROS, VERY_SLOW_MAX_MICROS);
    }
    if (draw < verySlowShare + slowShare) {
      return random.nextLong(FAST_MAX_MICROS, SLOW_MAX_MICROS);
    }
    return random.nextLong(FAST_MIN_MICROS, FAST_MAX_MICROS);
  }

  /** Decides whether one message is lost. */
  boolean isLost(SplittableRandom random) {
    return random.nextDouble() < lossProbability;
  }

  private static void requireShare(double share, String name) {
    if (!(share >= 0.0 && share <= 1.0)) {
      throw new IllegalArgumentException(name + " must be between 0 and 1, was " + share);
    }
  }
}
