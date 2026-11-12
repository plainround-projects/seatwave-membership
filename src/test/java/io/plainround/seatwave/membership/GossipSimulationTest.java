package io.plainround.seatwave.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** Gossip peers are distinct and never the sender, and a spread reaches every node. */
class GossipSimulationTest {
  private static final long SEED = 2_404;
  private static final int NODES = 10;
  private static final int DRAWS = 10_000;
  private static final int SMALL_CLUSTER = 4;
  private static final int TRIALS = 200;

  @Test
  void neverChoosesTheCallerAsItsOwnPeer() {
    GossipSimulation.Settings settings =
        new GossipSimulation.Settings(NODES, NODES - 1, 0.0, GossipSimulation.Mode.PUSH);
    SplittableRandom random = new SplittableRandom(SEED);

    for (int draw = 0; draw < DRAWS; draw++) {
      int caller = draw % NODES;
      assertThat(GossipSimulation.distinctPeers(caller, settings, random)).doesNotContain(caller);
    }
  }

  @Test
  void choosesFanoutDifferentPeers() {
    GossipSimulation.Settings settings =
        new GossipSimulation.Settings(
            SMALL_CLUSTER, SMALL_CLUSTER - 1, 0.0, GossipSimulation.Mode.PUSH);
    SplittableRandom random = new SplittableRandom(SEED);

    for (int draw = 0; draw < DRAWS; draw++) {
      int[] peers = GossipSimulation.distinctPeers(0, settings, random);
      assertThat(Arrays.stream(peers).distinct().count()).isEqualTo(SMALL_CLUSTER - 1);
    }
  }

  @Test
  void aSpreadReachesEveryNodeAndCountsItsMessages() {
    GossipSimulation.Settings settings =
        new GossipSimulation.Settings(NODES, 1, 0.0, GossipSimulation.Mode.PUSH_PULL);

    GossipSimulation.Summary summary = GossipSimulation.spread(settings, TRIALS, SEED);
    int[] informed = GossipSimulation.informedPerRound(settings, SEED);

    assertThat(summary.meanRounds()).isPositive();
    assertThat(summary.maxRounds()).isGreaterThanOrEqualTo(summary.p99Rounds());
    assertThat(summary.meanMessages()).isPositive();
    assertThat(informed[0]).isOne();
    assertThat(informed[informed.length - 1]).isEqualTo(NODES);
    assertThat(informed).isSorted();
  }

  @Test
  void rejectsAFanoutThatCannotBeDrawnAndALossOfOne() {
    assertThatThrownBy(
            () -> new GossipSimulation.Settings(NODES, NODES, 0.0, GossipSimulation.Mode.PUSH))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fanout");
    assertThatThrownBy(
            () -> new GossipSimulation.Settings(NODES, 1, 1.0, GossipSimulation.Mode.PUSH))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lossProbability");
  }
}
