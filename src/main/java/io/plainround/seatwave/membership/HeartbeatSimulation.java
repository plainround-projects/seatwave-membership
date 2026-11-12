package io.plainround.seatwave.membership;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * All-to-all heartbeats among SeatWave nodes on a simulated clock: every node sends a heartbeat to
 * every other node once per interval, delayed and sometimes lost by the network, and not sent at
 * all while the sender is paused. Every node watches every other node with its own detector. The
 * simulation measures, per detector setting and per group of watched nodes, two things: false
 * positives (a live node suspected) on a long healthy run, and detection time (crash to suspicion)
 * over many crashes. Seeded, so every run prints the same numbers, and every detector setting meets
 * exactly the same heartbeats. Stateless and thread-safe.
 */
final class HeartbeatSimulation {
  private static final int INDEX_BITS = 20;
  private static final long INDEX_MASK = (1L << INDEX_BITS) - 1;
  private static final double MICROS_PER_SECOND = 1_000_000.0;
  private static final double SECONDS_PER_HOUR = 3_600.0;
  private static final double P99 = 0.99;

  /**
   * One node of the cluster.
   *
   * @param nodeId the node's name, such as "n1"
   * @param group the group its results are reported under, such as "steady" or "busy"
   * @param pauses when its JVM pauses
   * @param outgoing the network its heartbeats cross, to every watcher
   */
  record Node(String nodeId, String group, PauseSchedule pauses, NetworkConditions outgoing) {}

  /**
   * The run's settings.
   *
   * @param heartbeatIntervalMicros time between two heartbeats from one node
   * @param healthyRunMicros simulated length of the run that counts false positives
   * @param warmUpMicros time a detector learns before anything is counted
   * @param crashTrials crashes simulated per group of watched nodes
   * @param seed the seed of every random draw
   */
  record Settings(
      long heartbeatIntervalMicros,
      long healthyRunMicros,
      long warmUpMicros,
      int crashTrials,
      long seed) {
    Settings {
      if (heartbeatIntervalMicros <= 0 || warmUpMicros < 0 || crashTrials <= 0) {
        throw new IllegalArgumentException("interval and crash trials must be positive");
      }
      if (healthyRunMicros <= warmUpMicros) {
        throw new IllegalArgumentException("the healthy run must be longer than the warm-up");
      }
      // Arrivals are packed with their index into one long for sorting; keep both in range.
      if (healthyRunMicros / heartbeatIntervalMicros >= INDEX_MASK) {
        throw new IllegalArgumentException("too many heartbeats per node for one run");
      }
    }
  }

  /**
   * What one detector setting did to one group of watched nodes.
   *
   * @param detector the setting's label
   * @param group the group of watched nodes
   * @param watchedNodes how many nodes the group has
   * @param falsePositivesPerHour suspicions of live nodes, summed over every watcher, per hour
   * @param meanSuspectAfterSeconds the detector's average effective timeout on this group
   * @param detectionMeanSeconds mean time from a crash to the watcher's suspicion
   * @param detectionP99Seconds 99th percentile of the same
   */
  record Row(
      String detector,
      String group,
      int watchedNodes,
      double falsePositivesPerHour,
      double meanSuspectAfterSeconds,
      double detectionMeanSeconds,
      double detectionP99Seconds) {}

  private HeartbeatSimulation() {}

  /** Runs the healthy run and the crash trials for every detector setting, against every group. */
  static List<Row> run(List<Node> nodes, List<FailureDetector> detectors, Settings settings) {
    Map<String, long[]> falsePositives = new LinkedHashMap<>();
    Map<String, double[]> suspectAfterSums = new LinkedHashMap<>();
    for (Node watched : nodes) {
      List<Long> sends = sendTimes(watched, settings.healthyRunMicros(), settings, 0);
      for (Node watcher : nodes) {
        if (watcher.equals(watched)) {
          continue;
        }
        long[] arrivals =
            freshArrivals(sends, watched.outgoing(), random(settings, watched, watcher, 0));
        for (FailureDetector prototype : detectors) {
          String key = prototype.label() + "|" + watched.group();
          long[] count = falsePositives.computeIfAbsent(key, ignored -> new long[1]);
          double[] sums = suspectAfterSums.computeIfAbsent(key, ignored -> new double[2]);
          countFalsePositives(prototype.fresh(), arrivals, settings.warmUpMicros(), count, sums);
        }
      }
    }
    List<Row> rows = new ArrayList<>();
    double hours =
        (settings.healthyRunMicros() - settings.warmUpMicros())
            / MICROS_PER_SECOND
            / SECONDS_PER_HOUR;
    for (FailureDetector prototype : detectors) {
      for (String group : nodes.stream().map(Node::group).distinct().toList()) {
        String key = prototype.label() + "|" + group;
        double[] detection = crashTrials(nodes, group, prototype, settings);
        double[] sums = suspectAfterSums.get(key);
        rows.add(
            new Row(
                prototype.label(),
                group,
                (int) nodes.stream().filter(node -> node.group().equals(group)).count(),
                falsePositives.get(key)[0] / hours,
                sums[0] / sums[1] / MICROS_PER_SECOND,
                Arrays.stream(detection).average().orElse(0.0),
                detection[(int) Math.ceil(P99 * detection.length) - 1]));
      }
    }
    return List.copyOf(rows);
  }

  /**
   * The times a node actually sends its heartbeats: on its interval, except that a heartbeat due
   * while the node is paused is sent when the pause ends, and only once however many fell due.
   */
  static List<Long> sendTimes(Node node, long untilMicros, Settings settings, long trial) {
    SplittableRandom random =
        new SplittableRandom(
            settings.seed() ^ node.nodeId().hashCode() ^ (trial * 0x9E3779B97F4A7C15L));
    List<PauseSchedule.Pause> pauses = node.pauses().draw(untilMicros, random);
    long phaseMicros = random.nextLong(settings.heartbeatIntervalMicros());
    List<Long> sends = new ArrayList<>();
    int pauseIndex = 0;
    for (long dueMicros = phaseMicros;
        dueMicros < untilMicros;
        dueMicros += settings.heartbeatIntervalMicros()) {
      while (pauseIndex < pauses.size() && pauses.get(pauseIndex).endMicros() <= dueMicros) {
        pauseIndex++;
      }
      long sendMicros = dueMicros;
      if (pauseIndex < pauses.size() && pauses.get(pauseIndex).startMicros() <= dueMicros) {
        sendMicros = pauses.get(pauseIndex).endMicros();
      }
      if (sends.isEmpty() || sends.getLast() != sendMicros) {
        sends.add(sendMicros);
      }
    }
    return sends;
  }

  /**
   * The arrival times a watcher's detector uses: each heartbeat is lost or delayed; heartbeats are
   * taken in arrival order, and one that arrives after a newer one is stale and ignored.
   */
  static long[] freshArrivals(
      List<Long> sends, NetworkConditions network, SplittableRandom random) {
    long[] packed = new long[sends.size()];
    int delivered = 0;
    for (int sendIndex = 0; sendIndex < sends.size(); sendIndex++) {
      if (network.isLost(random)) {
        continue;
      }
      long arrivalMicros = sends.get(sendIndex) + network.sampleDelayMicros(random);
      packed[delivered++] = (arrivalMicros << INDEX_BITS) | sendIndex;
    }
    long[] sorted = Arrays.copyOf(packed, delivered);
    Arrays.sort(sorted);
    long[] fresh = new long[delivered];
    int freshCount = 0;
    long newestIndex = -1;
    for (long entry : sorted) {
      long sendIndex = entry & INDEX_MASK;
      if (sendIndex > newestIndex) {
        newestIndex = sendIndex;
        fresh[freshCount++] = entry >>> INDEX_BITS;
      }
    }
    return Arrays.copyOf(fresh, freshCount);
  }

  private static void countFalsePositives(
      FailureDetector detector, long[] arrivals, long warmUpMicros, long[] count, double[] sums) {
    if (arrivals.length == 0) {
      return;
    }
    detector.recordHeartbeat(arrivals[0]);
    for (int arrivalIndex = 1; arrivalIndex < arrivals.length; arrivalIndex++) {
      long suspicionStartsMicros = detector.lastHeartbeatMicros() + detector.suspectAfterMicros();
      // The node is alive for the whole run, so any silence past the threshold is a false
      // positive: one per silence, however long it lasts.
      if (suspicionStartsMicros < arrivals[arrivalIndex] && suspicionStartsMicros > warmUpMicros) {
        count[0]++;
      }
      if (arrivals[arrivalIndex] > warmUpMicros) {
        sums[0] += detector.suspectAfterMicros();
        sums[1]++;
      }
      detector.recordHeartbeat(arrivals[arrivalIndex]);
    }
  }

  private static double[] crashTrials(
      List<Node> nodes, String group, FailureDetector prototype, Settings settings) {
    List<Node> watchedNodes = nodes.stream().filter(node -> node.group().equals(group)).toList();
    SplittableRandom chooser = new SplittableRandom(settings.seed() ^ group.hashCode());
    double[] detectionSeconds = new double[settings.crashTrials()];
    for (int trial = 0; trial < settings.crashTrials(); trial++) {
      Node watched = watchedNodes.get(chooser.nextInt(watchedNodes.size()));
      Node watcher = watched;
      while (watcher.equals(watched)) {
        watcher = nodes.get(chooser.nextInt(nodes.size()));
      }
      // The crash falls at a uniform moment in the heartbeat cycle after the warm-up.
      long crashMicros =
          settings.warmUpMicros() + chooser.nextLong(settings.heartbeatIntervalMicros());
      List<Long> sends = sendTimes(watched, crashMicros, settings, trial + 1);
      long[] arrivals =
          freshArrivals(sends, watched.outgoing(), random(settings, watched, watcher, trial + 1));
      FailureDetector detector = prototype.fresh();
      for (long arrivalMicros : arrivals) {
        detector.recordHeartbeat(arrivalMicros);
      }
      long suspectedAtMicros = detector.lastHeartbeatMicros() + detector.suspectAfterMicros();
      // A watcher that already suspected the node before the crash detects it at the crash.
      detectionSeconds[trial] = Math.max(suspectedAtMicros - crashMicros, 0) / MICROS_PER_SECOND;
    }
    Arrays.sort(detectionSeconds);
    return detectionSeconds;
  }

  private static SplittableRandom random(
      Settings settings, Node watched, Node watcher, long trial) {
    long mixed = settings.seed() * 31 + watched.nodeId().hashCode();
    mixed = mixed * 31 + watcher.nodeId().hashCode();
    return new SplittableRandom(mixed * 31 + trial);
  }
}
