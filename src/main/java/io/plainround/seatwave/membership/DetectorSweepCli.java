package io.plainround.seatwave.membership;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The module's central trade-off, printed: for fixed timeouts and phi thresholds, the detection
 * time after a crash against the false positives on a healthy but jittery network, first for ten
 * steady nodes, then for a cluster where two of the ten are busy. The only class here that prints.
 */
final class DetectorSweepCli {
  private static final long MICROS_PER_MILLI = 1_000;
  private static final long MICROS_PER_SECOND = 1_000_000;
  private static final int NODE_COUNT = 10;
  private static final int BUSY_NODE_COUNT = 2;
  // The walkthrough's setting: heartbeats every second.
  private static final long HEARTBEAT_INTERVAL_MICROS = MICROS_PER_SECOND;
  private static final long HEALTHY_HOURS = 200;
  private static final long SECONDS_PER_HOUR = 3_600;
  private static final long WARM_UP_SECONDS = 600;
  private static final int CRASH_TRIALS = 4_000;
  private static final long SEED = 2_404;
  private static final List<Double> FIXED_TIMEOUT_SECONDS = List.of(1.0, 2.0, 2.5, 3.0, 5.0);
  private static final List<Double> PHI_THRESHOLDS = List.of(3.0, 8.0, 12.0);
  // Pekko's defaults for a cluster: a window of 1,000 intervals, a 100 ms floor on the standard
  // deviation and a first-heartbeat estimate of 1 s. The acceptable pause is this module's choice.
  private static final int PHI_WINDOW = 1_000;
  private static final long PHI_MIN_STD_DEVIATION_MICROS = 100 * MICROS_PER_MILLI;
  private static final long PHI_ACCEPTABLE_PAUSE_MICROS = 1_000 * MICROS_PER_MILLI;

  private DetectorSweepCli() {}

  public static void main(String[] arguments) {
    List<FailureDetector> detectors = detectors();
    HeartbeatSimulation.Settings settings =
        new HeartbeatSimulation.Settings(
            HEARTBEAT_INTERVAL_MICROS,
            HEALTHY_HOURS * SECONDS_PER_HOUR * MICROS_PER_SECOND,
            WARM_UP_SECONDS * MICROS_PER_SECOND,
            CRASH_TRIALS,
            SEED);
    NetworkConditions network = NetworkConditions.onSale();
    System.out.println(
        String.format(
            Locale.ROOT,
            "%d nodes, all-to-all heartbeats every %.1f s, %d watchers per node; network one way:"
                + " p99 200 ms, loss %.1f%%; healthy run %d h after a %d s warm-up; %d crashes per"
                + " group",
            NODE_COUNT,
            HEARTBEAT_INTERVAL_MICROS / (double) MICROS_PER_SECOND,
            NODE_COUNT - 1,
            network.lossProbability() * 100,
            HEALTHY_HOURS,
            WARM_UP_SECONDS,
            CRASH_TRIALS));
    System.out.println(
        String.format(
            Locale.ROOT,
            "phi settings: window %d, min std deviation %d ms, acceptable pause %d ms",
            PHI_WINDOW,
            PHI_MIN_STD_DEVIATION_MICROS / MICROS_PER_MILLI,
            PHI_ACCEPTABLE_PAUSE_MICROS / MICROS_PER_MILLI));
    System.out.println();
    System.out.println(
        "CLUSTER A: ten steady nodes (a pause every 30 s; 0.1% of pauses 0.5 to 2 s)");
    printTable(HeartbeatSimulation.run(cluster(0), detectors, settings));
    System.out.println();
    System.out.println(
        "CLUSTER B: eight steady nodes and two busy ones (busy: a pause every 10 s;"
            + " 1% of pauses 0.5 to 2 s)");
    printTable(HeartbeatSimulation.run(cluster(BUSY_NODE_COUNT), detectors, settings));
  }

  private static List<FailureDetector> detectors() {
    List<FailureDetector> detectors = new ArrayList<>();
    for (double timeoutSeconds : FIXED_TIMEOUT_SECONDS) {
      detectors.add(new FixedTimeoutDetector((long) (timeoutSeconds * MICROS_PER_SECOND)));
    }
    for (double threshold : PHI_THRESHOLDS) {
      detectors.add(
          new PhiAccrualDetector(
              new PhiAccrualDetector.Settings(
                  threshold,
                  PHI_WINDOW,
                  PHI_MIN_STD_DEVIATION_MICROS,
                  PHI_ACCEPTABLE_PAUSE_MICROS,
                  HEARTBEAT_INTERVAL_MICROS)));
    }
    return List.copyOf(detectors);
  }

  private static List<HeartbeatSimulation.Node> cluster(int busyNodes) {
    List<HeartbeatSimulation.Node> nodes = new ArrayList<>();
    for (int nodeNumber = 1; nodeNumber <= NODE_COUNT; nodeNumber++) {
      boolean isBusy = nodeNumber > NODE_COUNT - busyNodes;
      nodes.add(
          new HeartbeatSimulation.Node(
              "n" + nodeNumber,
              isBusy ? "busy" : "steady",
              isBusy ? PauseSchedule.busy() : PauseSchedule.steady(),
              isBusy ? NetworkConditions.busy() : NetworkConditions.onSale()));
    }
    return List.copyOf(nodes);
  }

  private static void printTable(List<HeartbeatSimulation.Row> rows) {
    System.out.println(
        String.format(
            Locale.ROOT,
            "%-18s %-10s %15s %13s %14s %13s %12s",
            "detector",
            "watched",
            "suspects after",
            "false pos./h",
            "per node / h",
            "detect mean",
            "detect p99"));
    for (HeartbeatSimulation.Row row : rows) {
      System.out.println(
          String.format(
              Locale.ROOT,
              "%-18s %-10s %13.3f s %13.2f %14.3f %11.3f s %10.3f s",
              row.detector(),
              row.group() + " " + row.watchedNodes(),
              row.meanSuspectAfterSeconds(),
              row.falsePositivesPerHour(),
              row.falsePositivesPerHour() / row.watchedNodes(),
              row.detectionMeanSeconds(),
              row.detectionP99Seconds()));
    }
  }
}
