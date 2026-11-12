package io.plainround.seatwave.membership;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * What the per-node simulation measures on the model at 10 and 1,000 nodes: the time from a crash
 * to the first suspicion, to the first removal and to the removal in every live list, over several
 * crashes; and the protocol's messages and bytes a second per node at 1,000 nodes, with gossip of
 * deltas and with gossip of full tables. The only class here that prints.
 */
final class ClusterScaleCli {
  private static final long MICROS_PER_SECOND = 1_000_000;
  private static final long SEED = 2_404;
  private static final int SMALL_CLUSTER = 10;
  private static final int LARGE_CLUSTER = 1_000;
  private static final int SMALL_TRIALS = 40;
  private static final int LARGE_TRIALS = 8;
  // Every crash falls at a random moment in the second after 20 s, when every list is settled.
  private static final long CRASH_AFTER_MICROS = 20 * MICROS_PER_SECOND;
  // Long enough for 4 or 12 s of suspicion plus the spread, with a wide margin.
  private static final long OBSERVE_AFTER_CRASH_MICROS = 40 * MICROS_PER_SECOND;
  private static final long DELTA_TRAFFIC_RUN_MICROS = 30 * MICROS_PER_SECOND;
  private static final long TABLE_TRAFFIC_RUN_MICROS = 10 * MICROS_PER_SECOND;
  private static final double BYTES_PER_KILOBYTE = 1_000.0;

  private ClusterScaleCli() {}

  public static void main(String[] arguments) {
    System.out.println(
        "every node with its own list, memberlist LAN settings, on-sale network; one probe per"
            + " node per second; sizes assumed: message header "
            + MembershipCluster.HEADER_BYTES
            + " bytes, member entry "
            + MembershipCluster.ENTRY_BYTES
            + " bytes");
    System.out.println();
    System.out.println(
        String.format(
            Locale.ROOT,
            "%6s %7s %11s %11s %25s %25s %25s %13s %11s %8s",
            "nodes",
            "crashes",
            "suspicion",
            "news sent",
            "crash to first SUSPECT",
            "crash to first DEAD",
            "crash to DEAD everywhere",
            "live removals",
            "messages/s",
            "KB/s"));
    System.out.println(
        String.format(
            Locale.ROOT,
            "%6s %7s %11s %11s %25s %25s %25s %13s %11s %8s",
            "",
            "",
            "timeout",
            "per item",
            "mean (min to max)",
            "mean (min to max)",
            "mean (min to max)",
            "",
            "per node",
            "per node"));
    crashTrials(SMALL_CLUSTER, SMALL_TRIALS);
    crashTrials(LARGE_CLUSTER, LARGE_TRIALS);
    System.out.println();
    System.out.println(
        String.format(
            Locale.ROOT,
            "%6s %-12s %8s %31s %13s %15s",
            "nodes",
            "gossip",
            "run",
            "messages/s per node by kind",
            "messages/s",
            "KB/s per node"));
    traffic(MembershipCluster.Dissemination.DELTAS, DELTA_TRAFFIC_RUN_MICROS);
    traffic(MembershipCluster.Dissemination.FULL_TABLES, TABLE_TRAFFIC_RUN_MICROS);
  }

  private static void crashTrials(int nodeCount, int trials) {
    SplittableRandom chooser = new SplittableRandom(SEED + nodeCount);
    double[] suspected = new double[trials];
    double[] firstDead = new double[trials];
    double[] everywhere = new double[trials];
    long liveRemovals = 0;
    long messages = 0;
    long bytes = 0;
    double nodeSeconds = 0;
    MembershipCluster.Settings settings = MembershipCluster.Settings.memberlistLan(nodeCount, 0);
    for (int trial = 0; trial < trials; trial++) {
      String crashed = "n" + (1 + chooser.nextInt(nodeCount));
      long crashMicros = CRASH_AFTER_MICROS + chooser.nextLong(MICROS_PER_SECOND);
      MembershipCluster.Result result =
          MembershipCluster.run(
              settings.withSeed(SEED + trial),
              new MembershipCluster.Scenario(
                  crashMicros + OBSERVE_AFTER_CRASH_MICROS,
                  List.of(new MembershipCluster.Crash(crashed, crashMicros)),
                  List.of()));
      Set<String> everyone = new LinkedHashSet<>(result.nodeIds());
      Set<String> survivors = new LinkedHashSet<>(everyone);
      survivors.remove(crashed);
      suspected[trial] =
          seconds(
              result.firstChange(everyone, Set.of(crashed), MembershipList.Status.SUSPECT, 0)
                  - crashMicros);
      firstDead[trial] =
          seconds(
              result.firstChange(everyone, Set.of(crashed), MembershipList.Status.DEAD, 0)
                  - crashMicros);
      long allDead =
          result.everyObserverHolds(survivors, Set.of(crashed), MembershipList.Status.DEAD, 0);
      everywhere[trial] = allDead < 0 ? Double.NaN : seconds(allDead - crashMicros);
      messages += result.totalMessages();
      bytes += result.bytesSent();
      nodeSeconds += nodeCount * seconds(crashMicros + OBSERVE_AFTER_CRASH_MICROS);
      liveRemovals +=
          result.changes().stream()
              .filter(change -> change.status() == MembershipList.Status.DEAD)
              .filter(change -> !change.subject().equals(crashed))
              .count();
    }
    System.out.println(
        String.format(
            Locale.ROOT,
            "%6d %7d %9.0f s %11d %25s %25s %25s %13d %11.2f %8.2f",
            nodeCount,
            trials,
            seconds(settings.suspicionTimeoutMicros()),
            settings.retransmitLimit(),
            spread(suspected),
            spread(firstDead),
            spread(everywhere),
            liveRemovals,
            messages / nodeSeconds,
            bytes / nodeSeconds / BYTES_PER_KILOBYTE));
  }

  private static void traffic(MembershipCluster.Dissemination dissemination, long runMicros) {
    MembershipCluster.Result result =
        MembershipCluster.run(
            MembershipCluster.Settings.memberlistLan(LARGE_CLUSTER, SEED)
                .withDissemination(dissemination),
            new MembershipCluster.Scenario(runMicros, List.of(), List.of()));
    double nodeSeconds = LARGE_CLUSTER * seconds(runMicros);
    List<String> kinds = new ArrayList<>();
    for (MembershipCluster.MessageKind kind : MembershipCluster.MessageKind.values()) {
      kinds.add(
          String.format(
              Locale.ROOT,
              "%s %.2f",
              kind.name().toLowerCase(Locale.ROOT).replace('_', '-'),
              result.messages().getOrDefault(kind, 0L) / nodeSeconds));
    }
    System.out.println(
        String.format(
            Locale.ROOT,
            "%6d %-12s %6.0f s %31s %13.2f %15.2f",
            LARGE_CLUSTER,
            dissemination.name().toLowerCase(Locale.ROOT).replace('_', ' '),
            seconds(runMicros),
            String.join(", ", kinds),
            result.totalMessages() / nodeSeconds,
            result.bytesSent() / nodeSeconds / BYTES_PER_KILOBYTE));
  }

  private static String spread(double[] values) {
    double[] sorted = values.clone();
    Arrays.sort(sorted);
    double mean = Arrays.stream(sorted).average().orElse(Double.NaN);
    return String.format(
        Locale.ROOT, "%.3f s (%.3f to %.3f)", mean, sorted[0], sorted[sorted.length - 1]);
  }

  private static double seconds(long micros) {
    return micros / (double) MICROS_PER_SECOND;
  }
}
