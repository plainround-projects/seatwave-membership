package io.plainround.seatwave.membership;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Failure mode 5 on ten SeatWave nodes, each with its own membership list: a network cut splits the
 * cluster for 60 s, first 5 against 5, then 7 against 3; each side suspects and removes the other,
 * the cut heals, and the lists converge again. The same cut is then replayed on the seed's shared
 * view, to show what one list for everybody hid. The only class here that prints.
 */
final class PartitionCli {
  private static final long MICROS_PER_MILLI = 1_000;
  private static final long MICROS_PER_SECOND = 1_000_000;
  private static final int NODE_COUNT = 10;
  private static final long SEED = 2_404;
  private static final long CUT_FROM_MICROS = 60 * MICROS_PER_SECOND;
  private static final long CUT_UNTIL_MICROS = 120 * MICROS_PER_SECOND;
  private static final long RUN_MICROS = 300 * MICROS_PER_SECOND;
  private static final int EVEN_SIDE = 5;
  private static final int LARGE_SIDE = 7;
  // The node whose history in the other side's list is printed in full, and that list's owner.
  private static final String FOLLOWED_NODE = "n1";

  private PartitionCli() {}

  public static void main(String[] arguments) {
    MembershipCluster.Settings settings =
        MembershipCluster.Settings.memberlistLan(NODE_COUNT, SEED);
    System.out.println(
        String.format(
            Locale.ROOT,
            "%d nodes, each with its own list; memberlist LAN settings: probe every %d ms, ack"
                + " timeout %d ms, %d helpers, suspicion timeout %.0f s, gossip to %d nodes every"
                + " %d ms, each item sent %d times, push-pull every %.0f s, one dead member retried"
                + " every %.0f s; on-sale network; seed %d",
            NODE_COUNT,
            settings.probeIntervalMicros() / MICROS_PER_MILLI,
            settings.probeTimeoutMicros() / MICROS_PER_MILLI,
            settings.indirectProbes(),
            seconds(settings.suspicionTimeoutMicros()),
            settings.gossipFanout(),
            settings.gossipIntervalMicros() / MICROS_PER_MILLI,
            settings.retransmitLimit(),
            seconds(settings.pushPullIntervalMicros()),
            seconds(settings.reconnectIntervalMicros()),
            SEED));
    System.out.println(
        String.format(
            Locale.ROOT,
            "cut from %.3f s to %.3f s; run %.0f s; times below are counted from the cut (+)"
                + " or from the heal (heal +)",
            seconds(CUT_FROM_MICROS),
            seconds(CUT_UNTIL_MICROS),
            seconds(RUN_MICROS)));
    for (int sideSize : List.of(EVEN_SIDE, LARGE_SIDE)) {
      for (MembershipCluster.ViewModel model : MembershipCluster.ViewModel.values()) {
        System.out.println();
        report(sideSize, settings.withViewModel(model));
      }
    }
  }

  private static void report(int sideSize, MembershipCluster.Settings settings) {
    Set<String> sideA = nodes(1, sideSize);
    Set<String> sideB = nodes(sideSize + 1, NODE_COUNT);
    MembershipCluster.Scenario scenario =
        new MembershipCluster.Scenario(
            RUN_MICROS,
            List.of(),
            List.of(new MembershipCluster.Partition(sideA, CUT_FROM_MICROS, CUT_UNTIL_MICROS)));
    MembershipCluster.Result result = MembershipCluster.run(settings, scenario);
    System.out.println(
        String.format(
            Locale.ROOT,
            "PARTITION %d against %d, %s view (side A %s, side B %s)",
            sideA.size(),
            sideB.size(),
            settings.viewModel() == MembershipCluster.ViewModel.SHARED ? "one shared" : "per-node",
            String.join(" ", sideA),
            String.join(" ", sideB)));
    printSide("A", sideA, sideB, result);
    printSide("B", sideB, sideA, result);
    long removalsInCut =
        result.countChanges(MembershipList.Status.DEAD, CUT_FROM_MICROS, CUT_UNTIL_MICROS);
    long removalsBefore = result.countChanges(MembershipList.Status.DEAD, 0, CUT_FROM_MICROS);
    long removalsAfter =
        result.countChanges(MembershipList.Status.DEAD, CUT_UNTIL_MICROS, RUN_MICROS);
    long removedNodes =
        result.changes().stream()
            .filter(change -> change.status() == MembershipList.Status.DEAD)
            .map(MembershipCluster.Change::subject)
            .distinct()
            .count();
    System.out.println(
        String.format(
            Locale.ROOT,
            "  removals of live nodes (list entries turned DEAD): %d before the cut, %d during it,"
                + " %d after the heal; %d of %d nodes removed by at least one list",
            removalsBefore,
            removalsInCut,
            removalsAfter,
            removedNodes,
            NODE_COUNT));
    System.out.println(
        String.format(
            Locale.ROOT,
            "  suspicions applied %d, refutations %d (every one by the accused node itself: %s),"
                + " stale ALIVE refused for a DEAD entry %d, messages %d",
            result.countChanges(MembershipList.Status.SUSPECT, 0, RUN_MICROS),
            result.refutations().size(),
            result.refutations().stream()
                    .allMatch(refutation -> refutation.refuter().equals(refutation.subject()))
                ? "yes"
                : "no",
            result.staleAliveRefusedForDead(),
            result.totalMessages()));
    System.out.println(
        String.format(
            Locale.ROOT,
            "  after the heal: first refutation heal +%s; every list agrees, all ALIVE, heal"
                + " +%s; incarnations at the end: %s",
            sinceHeal(
                result.refutations().stream()
                    .mapToLong(MembershipCluster.Refutation::atMicros)
                    .filter(atMicros -> atMicros >= CUT_UNTIL_MICROS)
                    .findFirst()
                    .orElse(-1)),
            sinceHeal(result.convergedAtMicros()),
            incarnations(result)));
    printFollowed(sideB, result);
  }

  private static void printSide(
      String label, Set<String> side, Set<String> other, MembershipCluster.Result result) {
    System.out.println(
        String.format(
            Locale.ROOT,
            "  side %s about side %s: first SUSPECT +%s, first DEAD +%s, every %s list holds every"
                + " %s node DEAD +%s",
            label,
            label.equals("A") ? "B" : "A",
            sinceCut(
                result.firstChange(side, other, MembershipList.Status.SUSPECT, CUT_FROM_MICROS)),
            sinceCut(result.firstChange(side, other, MembershipList.Status.DEAD, CUT_FROM_MICROS)),
            label,
            label.equals("A") ? "B" : "A",
            sinceCut(
                result.everyObserverHolds(
                    side, other, MembershipList.Status.DEAD, CUT_FROM_MICROS))));
  }

  private static void printFollowed(Set<String> otherSide, MembershipCluster.Result result) {
    String observer = otherSide.iterator().next();
    String history =
        result.changes().stream()
            .filter(change -> change.observer().equals(observer))
            .filter(change -> change.subject().equals(FOLLOWED_NODE))
            .map(change ->
                String.format(
                    Locale.ROOT,
                    "%s %d at %.3f s",
                    change.status(),
                    change.incarnation(),
                    seconds(change.atMicros())))
            .collect(Collectors.joining("; "));
    System.out.println(
        String.format(
            Locale.ROOT,
            "  %s as %s's list saw it: %s",
            FOLLOWED_NODE,
            observer,
            history.isEmpty() ? "no change after the start" : history));
  }

  private static String incarnations(MembershipCluster.Result result) {
    return result.nodeIds().stream()
        .map(nodeId ->
            nodeId
                + "="
                + result.refutations().stream()
                    .filter(refutation -> refutation.subject().equals(nodeId))
                    .mapToLong(MembershipCluster.Refutation::incarnation)
                    .max()
                    .orElse(0))
        .collect(Collectors.joining(" "));
  }

  private static Set<String> nodes(int firstNumber, int lastNumber) {
    Set<String> side = new LinkedHashSet<>();
    for (int nodeNumber = firstNumber; nodeNumber <= lastNumber; nodeNumber++) {
      side.add("n" + nodeNumber);
    }
    return side;
  }

  private static String sinceCut(long atMicros) {
    return atMicros < 0
        ? "never"
        : String.format(Locale.ROOT, "%.3f s", seconds(atMicros - CUT_FROM_MICROS));
  }

  private static String sinceHeal(long atMicros) {
    return atMicros < 0
        ? "never"
        : String.format(Locale.ROOT, "%.3f s", seconds(atMicros - CUT_UNTIL_MICROS));
  }

  private static double seconds(long micros) {
    return micros / (double) MICROS_PER_SECOND;
  }
}
