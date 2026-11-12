package io.plainround.seatwave.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * The heartbeat engine on a small cluster: a timeout far above every live silence never accuses a
 * live node and detects a crash about half an interval before the timeout; a timeout equal to the
 * interval accuses live nodes constantly; a lossless network delivers every heartbeat in order.
 */
class HeartbeatSimulationTest {
  private static final long MICROS_PER_SECOND = 1_000_000;
  private static final long INTERVAL_MICROS = MICROS_PER_SECOND;
  private static final long HEALTHY_RUN_MICROS = 3_600 * MICROS_PER_SECOND;
  private static final long WARM_UP_MICROS = 60 * MICROS_PER_SECOND;
  private static final int CRASH_TRIALS = 400;
  private static final long SEED = 2_404;
  private static final long LONG_TIMEOUT_MICROS = 5 * MICROS_PER_SECOND;
  // Expected detection: timeout - interval / 2 + mean one-way delay (about 25 ms).
  private static final double EXPECTED_DETECTION_SECONDS = 4.525;
  private static final double DETECTION_TOLERANCE_SECONDS = 0.05;
  private static final double MANY_FALSE_POSITIVES_PER_HOUR = 1_000.0;
  private static final long PAUSE_START_MICROS = 2_500_000;
  private static final long PAUSE_MICROS = 2_000_000;

  private final List<HeartbeatSimulation.Node> nodes = List.of(node("n1"), node("n2"), node("n3"));
  private final HeartbeatSimulation.Settings settings =
      new HeartbeatSimulation.Settings(
          INTERVAL_MICROS, HEALTHY_RUN_MICROS, WARM_UP_MICROS, CRASH_TRIALS, SEED);

  @Test
  void aTimeoutAboveEveryLiveSilenceAccusesNobodyAndDetectsAboutHalfAnIntervalEarly() {
    List<HeartbeatSimulation.Row> rows =
        HeartbeatSimulation.run(
            nodes, List.of(new FixedTimeoutDetector(LONG_TIMEOUT_MICROS)), settings);

    assertThat(rows)
        .singleElement()
        .satisfies(row -> {
          assertThat(row.falsePositivesPerHour()).isZero();
          assertThat(row.detectionMeanSeconds())
              .isCloseTo(EXPECTED_DETECTION_SECONDS, within(DETECTION_TOLERANCE_SECONDS));
          assertThat(row.watchedNodes()).isEqualTo(nodes.size());
        });
  }

  @Test
  void aTimeoutEqualToTheIntervalAccusesLiveNodesConstantly() {
    List<HeartbeatSimulation.Row> rows =
        HeartbeatSimulation.run(
            nodes, List.of(new FixedTimeoutDetector(INTERVAL_MICROS)), settings);

    assertThat(rows.getFirst().falsePositivesPerHour())
        .isGreaterThan(MANY_FALSE_POSITIVES_PER_HOUR);
  }

  @Test
  void theSameSeedGivesTheSameTable() {
    List<FailureDetector> detectors = List.of(new FixedTimeoutDetector(LONG_TIMEOUT_MICROS));

    assertThat(HeartbeatSimulation.run(nodes, detectors, settings))
        .isEqualTo(HeartbeatSimulation.run(nodes, detectors, settings));
  }

  @Test
  void aLosslessNetworkDeliversEveryHeartbeatInSendOrder() {
    PauseSchedule.Pause pause = new PauseSchedule.Pause(PAUSE_START_MICROS, PAUSE_MICROS);
    long[] arrivals =
        HeartbeatSimulation.freshArrivals(
            List.of(0L, INTERVAL_MICROS, 2 * INTERVAL_MICROS, pause.endMicros()),
            new NetworkConditions(0.0, 0.0, 0.0),
            new SplittableRandom(SEED));

    assertThat(arrivals).hasSize(4).isSorted();
    assertThat(pause.endMicros()).isEqualTo(PAUSE_START_MICROS + PAUSE_MICROS);
  }

  @Test
  void rejectsSharesAndSchedulesThatCannotExist() {
    assertThatThrownBy(() -> new NetworkConditions(0.6, 0.6, 0.0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not exceed 1");
    assertThatThrownBy(() -> new NetworkConditions(0.1, 0.1, 2.0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lossProbability");
    assertThatThrownBy(() -> new PauseSchedule(0.0, 0.0, 0.0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("meanSecondsBetweenPauses");
    assertThatThrownBy(() -> new FixedTimeoutDetector(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("timeoutMicros");
    assertThatThrownBy(
            () -> new HeartbeatSimulation.Settings(INTERVAL_MICROS, 1, WARM_UP_MICROS, 1, SEED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("longer than the warm-up");
  }

  private static HeartbeatSimulation.Node node(String nodeId) {
    return new HeartbeatSimulation.Node(
        nodeId, "steady", PauseSchedule.steady(), NetworkConditions.onSale().withLoss(0.0));
  }
}
