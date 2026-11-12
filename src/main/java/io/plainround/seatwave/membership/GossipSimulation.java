package io.plainround.seatwave.membership;

import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * Rounds of gossip that spread one fact (a node's death, or a new version of a small piece of
 * state) from one node to all of them. In each round every node picks {@code fanout} other nodes at
 * random. With push, a node that knows the fact sends it; with push-pull, every pair that talks
 * exchanges what it knows both ways. Each message is lost independently. The simulation counts
 * rounds until every node knows, and messages sent until then. Stateless and thread-safe.
 */
final class GossipSimulation {

  /** How two nodes that talk share the fact. */
  enum Mode {
    PUSH,
    PUSH_PULL
  }

  /**
   * One experiment's settings.
   *
   * @param nodes cluster size
   * @param fanout peers each node contacts per round
   * @param lossProbability chance that one message is lost
   * @param mode push or push-pull
   */
  record Settings(int nodes, int fanout, double lossProbability, Mode mode) {
    Settings {
      if (nodes < 2 || fanout < 1 || fanout >= nodes) {
        throw new IllegalArgumentException("need nodes >= 2 and 1 <= fanout < nodes");
      }
      if (!(lossProbability >= 0.0 && lossProbability < 1.0)) {
        throw new IllegalArgumentException("lossProbability must be in [0, 1)");
      }
    }
  }

  /**
   * Many trials of one setting.
   *
   * @param meanRounds average rounds until every node knows
   * @param p99Rounds 99th percentile of the same
   * @param maxRounds the slowest trial
   * @param meanMessages average messages sent until every node knows
   */
  record Summary(double meanRounds, int p99Rounds, int maxRounds, double meanMessages) {}

  private static final double P99 = 0.99;
  // A guard against a setting that can never finish; no setting in this module comes near it.
  private static final int MAX_ROUNDS = 10_000;

  private GossipSimulation() {}

  /** Runs {@code trials} independent spreads from node 0, seeded by {@code seed}. */
  static Summary spread(Settings settings, int trials, long seed) {
    SplittableRandom random = new SplittableRandom(seed);
    int[] rounds = new int[trials];
    long messages = 0;
    for (int trial = 0; trial < trials; trial++) {
      long[] outcome = oneSpread(settings, random);
      rounds[trial] = (int) outcome[0];
      messages += outcome[1];
    }
    Arrays.sort(rounds);
    return new Summary(
        Arrays.stream(rounds).average().orElse(0.0),
        rounds[(int) Math.ceil(P99 * trials) - 1],
        rounds[trials - 1],
        messages / (double) trials);
  }

  /** The informed count after each round of one spread, for a figure; index 0 is the start. */
  static int[] informedPerRound(Settings settings, long seed) {
    SplittableRandom random = new SplittableRandom(seed);
    boolean[] informed = new boolean[settings.nodes()];
    informed[0] = true;
    int[] counts = new int[MAX_ROUNDS + 1];
    counts[0] = 1;
    int round = 0;
    while (counts[round] < settings.nodes() && round < MAX_ROUNDS) {
      oneRound(settings, informed, random);
      round++;
      counts[round] = count(informed);
    }
    return Arrays.copyOf(counts, round + 1);
  }

  private static long[] oneSpread(Settings settings, SplittableRandom random) {
    boolean[] informed = new boolean[settings.nodes()];
    informed[0] = true;
    long messages = 0;
    int round = 0;
    while (count(informed) < settings.nodes()) {
      if (round == MAX_ROUNDS) {
        throw new IllegalStateException("gossip did not converge in " + MAX_ROUNDS + " rounds");
      }
      messages += oneRound(settings, informed, random);
      round++;
    }
    return new long[] {round, messages};
  }

  private static long oneRound(Settings settings, boolean[] informed, SplittableRandom random) {
    // Decisions in a round use what nodes knew at its start, as if all talk at the same moment.
    boolean[] before = informed.clone();
    long messages = 0;
    for (int caller = 0; caller < settings.nodes(); caller++) {
      if (settings.mode() == Mode.PUSH && !before[caller]) {
        continue;
      }
      for (int peer : distinctPeers(caller, settings, random)) {
        messages++;
        if (before[caller] && !isLost(settings, random)) {
          informed[peer] = true;
        }
        if (settings.mode() == Mode.PUSH_PULL) {
          // The peer's reply is a second message, lost or not on its own.
          messages++;
          if (before[peer] && !isLost(settings, random)) {
            informed[caller] = true;
          }
        }
      }
    }
    return messages;
  }

  /**
   * Draws {@code settings.fanout()} different peers for {@code caller}, never the caller itself.
   */
  static int[] distinctPeers(int caller, Settings settings, SplittableRandom random) {
    int[] peers = new int[settings.fanout()];
    int chosen = 0;
    while (chosen < peers.length) {
      int candidate = random.nextInt(settings.nodes());
      boolean isRepeat = candidate == caller;
      for (int earlier = 0; earlier < chosen && !isRepeat; earlier++) {
        isRepeat = peers[earlier] == candidate;
      }
      if (!isRepeat) {
        peers[chosen++] = candidate;
      }
    }
    return peers;
  }

  private static boolean isLost(Settings settings, SplittableRandom random) {
    return random.nextDouble() < settings.lossProbability();
  }

  private static int count(boolean[] informed) {
    int known = 0;
    for (boolean isInformed : informed) {
      if (isInformed) {
        known++;
      }
    }
    return known;
  }
}
