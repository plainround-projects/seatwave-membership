package io.plainround.seatwave.membership;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.SplittableRandom;

/**
 * SWIM-style probing on ten SeatWave nodes for one simulated hour, under three policies: remove a
 * node on one failed direct probe; also ask three other nodes to probe it before removing; and full
 * SWIM, which adds a suspicion stage that the node can refute before a 4 s timeout. The link
 * between n1 and n4 loses half its messages each way, every other link is the on-sale network, and
 * n7 crashes after 30 minutes. The cluster's view is one shared {@link MembershipList}, which
 * stands for the view gossip keeps in step on every node (dissemination itself is {@link
 * GossipCli}'s subject). The only class here that prints.
 */
final class SwimProbeCli {
  private static final long MICROS_PER_MILLI = 1_000;
  private static final long MICROS_PER_SECOND = 1_000_000;
  private static final int NODE_COUNT = 10;
  // HashiCorp memberlist's LAN defaults: probe every 1 s, 500 ms for the direct ack, 3 helpers.
  private static final long PROBE_INTERVAL_MICROS = MICROS_PER_SECOND;
  private static final long PROBE_TIMEOUT_MICROS = 500 * MICROS_PER_MILLI;
  private static final int INDIRECT_HELPERS = 3;
  // Suspicion timeout for ten nodes: memberlist's 4 x max(1, log10(10)) x 1 s.
  private static final long SUSPICION_TIMEOUT_MICROS = 4 * MICROS_PER_SECOND;
  // Assumed: a suspected or removed node hears of it, through gossip, within one probe interval.
  private static final long NEWS_DELAY_MICROS = MICROS_PER_SECOND;
  private static final long RUN_MICROS = 3_600 * MICROS_PER_SECOND;
  private static final long CRASH_MICROS = 1_800 * MICROS_PER_SECOND;
  private static final String CRASHED_NODE = "n7";
  private static final String DEGRADED_END_A = "n1";
  private static final String DEGRADED_END_B = "n4";
  private static final double DEGRADED_LOSS = 0.5;
  private static final long SEED = 2_404;

  /** What a node does when a probe gets no ack. */
  enum Policy {
    DIRECT_ONLY,
    DIRECT_AND_INDIRECT,
    SWIM_WITH_SUSPICION
  }

  private record Event(long atMicros, String nodeId, Kind kind) {}

  private enum Kind {
    PROBE,
    HEAR_NEWS,
    CHECK_SUSPICION
  }

  private record Tally(
      long probesOfLiveNodes,
      long directFailures,
      long suspicionsOfLiveNodes,
      long removalsOfLiveNodes,
      long membershipChanges,
      double crashSuspectedAfterSeconds,
      double crashRemovedAfterSeconds) {}

  private final Policy policy;
  private final boolean isTracing;
  private final MembershipList view;
  private final SplittableRandom network = new SplittableRandom(SEED);
  private final SplittableRandom helpers = new SplittableRandom(SEED + 1);
  private final Map<String, List<String>> probeOrders = new HashMap<>();
  private final Map<String, Long> incarnations = new HashMap<>();
  private final PriorityQueue<Event> events =
      new PriorityQueue<>((first, second) -> Long.compare(first.atMicros(), second.atMicros()));
  private final List<String> nodeIds = new ArrayList<>();
  private long probesOfLiveNodes;
  private long directFailures;
  private long suspicionsOfLiveNodes;
  private long removalsOfLiveNodes;
  private long membershipChanges;
  private long crashSuspectedAtMicros = -1;
  private long crashRemovedAtMicros = -1;
  private boolean hasTraced;

  private SwimProbeCli(Policy policy, boolean isTracing) {
    this.policy = policy;
    this.isTracing = isTracing;
    this.view =
        new MembershipList(policy == Policy.SWIM_WITH_SUSPICION ? SUSPICION_TIMEOUT_MICROS : 0);
  }

  public static void main(String[] arguments) {
    System.out.println(
        String.format(
            Locale.ROOT,
            "%d nodes, one probe per node every %d ms, direct ack timeout %d ms, %d helpers,"
                + " suspicion timeout %d s; link %s-%s loses %.0f%% each way; %s crashes at %d s",
            NODE_COUNT,
            PROBE_INTERVAL_MICROS / MICROS_PER_MILLI,
            PROBE_TIMEOUT_MICROS / MICROS_PER_MILLI,
            INDIRECT_HELPERS,
            SUSPICION_TIMEOUT_MICROS / MICROS_PER_SECOND,
            DEGRADED_END_A,
            DEGRADED_END_B,
            DEGRADED_LOSS * 100,
            CRASHED_NODE,
            CRASH_MICROS / MICROS_PER_SECOND));
    System.out.println();
    List<Tally> tallies = new ArrayList<>();
    for (Policy policy : Policy.values()) {
      tallies.add(new SwimProbeCli(policy, policy == Policy.SWIM_WITH_SUSPICION).simulate());
    }
    System.out.println();
    System.out.println(
        String.format(
            Locale.ROOT,
            "%-20s %8s %9s %12s %10s %8s %13s %13s",
            "policy (1 hour)",
            "probes",
            "direct",
            "live nodes",
            "live nodes",
            "member",
            "n7 crash:",
            "n7 crash:"));
    System.out.println(
        String.format(
            Locale.ROOT,
            "%-20s %8s %9s %12s %10s %8s %13s %13s",
            "",
            "of live",
            "failures",
            "suspected",
            "removed",
            "changes",
            "suspected",
            "removed"));
    for (int index = 0; index < tallies.size(); index++) {
      Tally tally = tallies.get(index);
      System.out.println(
          String.format(
              Locale.ROOT,
              "%-20s %8d %9d %12d %10d %8d %11.3f s %11.3f s",
              Policy.values()[index].name(),
              tally.probesOfLiveNodes(),
              tally.directFailures(),
              tally.suspicionsOfLiveNodes(),
              tally.removalsOfLiveNodes(),
              tally.membershipChanges(),
              tally.crashSuspectedAfterSeconds(),
              tally.crashRemovedAfterSeconds()));
    }
  }

  private Tally simulate() {
    SplittableRandom phases = new SplittableRandom(SEED + 2);
    for (int nodeNumber = 1; nodeNumber <= NODE_COUNT; nodeNumber++) {
      String nodeId = "n" + nodeNumber;
      nodeIds.add(nodeId);
      incarnations.put(nodeId, 0L);
      view.markAlive(nodeId, 0, 0);
      events.add(new Event(phases.nextLong(PROBE_INTERVAL_MICROS), nodeId, Kind.PROBE));
    }
    while (!events.isEmpty() && events.peek().atMicros() < RUN_MICROS) {
      Event event = events.poll();
      switch (event.kind()) {
        case PROBE -> probe(event.nodeId(), event.atMicros());
        case HEAR_NEWS -> hearNews(event.nodeId(), event.atMicros());
        case CHECK_SUSPICION -> checkSuspicion(event.nodeId(), event.atMicros());
      }
    }
    return new Tally(
        probesOfLiveNodes,
        directFailures,
        suspicionsOfLiveNodes,
        removalsOfLiveNodes,
        membershipChanges,
        secondsAfterCrash(crashSuspectedAtMicros),
        secondsAfterCrash(crashRemovedAtMicros));
  }

  private void probe(String prober, long nowMicros) {
    events.add(new Event(nowMicros + PROBE_INTERVAL_MICROS, prober, Kind.PROBE));
    if (isCrashed(prober, nowMicros)) {
      return;
    }
    String target = nextTarget(prober);
    if (target.isEmpty()) {
      return;
    }
    boolean isTargetAlive = !isCrashed(target, nowMicros);
    if (isTargetAlive) {
      probesOfLiveNodes++;
    }
    if (roundTripMicros(prober, target, nowMicros) <= PROBE_TIMEOUT_MICROS) {
      return;
    }
    if (isTargetAlive) {
      directFailures++;
    }
    if (policy != Policy.DIRECT_ONLY && indirectProbeSucceeds(prober, target, nowMicros)) {
      return;
    }
    // The prober's detector has crossed its threshold: the probe failed, direct (known after the
    // 500 ms ack timeout) or also indirect (known at the end of the period).
    long failedAtMicros =
        nowMicros + (policy == Policy.DIRECT_ONLY ? PROBE_TIMEOUT_MICROS : PROBE_INTERVAL_MICROS);
    MembershipList.Suspicion evidence = new MembershipList.Suspicion(prober, 1.0, 1.0);
    long knownIncarnation = view.member(target).orElseThrow().incarnation();
    if (view.suspect(target, knownIncarnation, evidence, failedAtMicros)
        instanceof MembershipList.Applied) {
      membershipChanges++;
      recordSuspicion(target, isTargetAlive, failedAtMicros);
      events.add(new Event(failedAtMicros + NEWS_DELAY_MICROS, target, Kind.HEAR_NEWS));
      events.add(
          new Event(failedAtMicros + SUSPICION_TIMEOUT_MICROS, target, Kind.CHECK_SUSPICION));
    }
  }

  private boolean indirectProbeSucceeds(String prober, String target, long nowMicros) {
    List<String> candidates = new ArrayList<>(nodeIds);
    candidates.removeIf(nodeId ->
        nodeId.equals(prober)
            || nodeId.equals(target)
            || view.member(nodeId).orElseThrow().status() == MembershipList.Status.DEAD);
    Collections.shuffle(candidates, new Random(helpers.nextLong()));
    List<String> chosen = candidates.subList(0, Math.min(INDIRECT_HELPERS, candidates.size()));
    for (String helper : chosen) {
      // Prober to helper, helper to target and back, helper to prober: all in the half period
      // that is left after the direct probe's 500 ms.
      long pathMicros =
          oneWayMicros(prober, helper)
              + roundTripMicros(helper, target, nowMicros)
              + oneWayMicros(helper, prober);
      boolean isHelperAlive = !isCrashed(helper, nowMicros);
      if (isHelperAlive && pathMicros <= PROBE_INTERVAL_MICROS - PROBE_TIMEOUT_MICROS) {
        trace(nowMicros, prober, target, chosen, helper, pathMicros);
        return true;
      }
    }
    return false;
  }

  private void hearNews(String nodeId, long nowMicros) {
    if (isCrashed(nodeId, nowMicros)) {
      return;
    }
    // A live node that hears it is suspected, or was removed, refutes with a higher incarnation.
    long nextIncarnation = incarnations.merge(nodeId, 1L, Long::sum);
    if (view.markAlive(nodeId, nextIncarnation, nowMicros) instanceof MembershipList.Applied) {
      membershipChanges++;
    }
  }

  private void checkSuspicion(String nodeId, long nowMicros) {
    if (view.confirmDead(nodeId, nowMicros) instanceof MembershipList.Applied) {
      membershipChanges++;
      if (isCrashed(nodeId, nowMicros)) {
        if (crashRemovedAtMicros < 0) {
          crashRemovedAtMicros = nowMicros;
        }
      } else {
        removalsOfLiveNodes++;
      }
    }
  }

  private void recordSuspicion(String target, boolean isTargetAlive, long atMicros) {
    if (isTargetAlive) {
      suspicionsOfLiveNodes++;
    } else if (crashSuspectedAtMicros < 0) {
      crashSuspectedAtMicros = atMicros;
    }
    if (policy != Policy.SWIM_WITH_SUSPICION) {
      // Without a suspicion stage the suspicion is the removal (a timeout of zero).
      checkSuspicion(target, atMicros);
    }
  }

  private String nextTarget(String prober) {
    List<String> order = probeOrders.computeIfAbsent(prober, ignored -> new ArrayList<>());
    while (!order.isEmpty()) {
      String candidate = order.removeFirst();
      if (view.member(candidate).orElseThrow().status() != MembershipList.Status.DEAD) {
        return candidate;
      }
    }
    // SWIM walks a shuffled list of the members round-robin and reshuffles after each pass.
    List<String> others = new ArrayList<>(nodeIds);
    others.removeIf(nodeId ->
        nodeId.equals(prober)
            || view.member(nodeId).orElseThrow().status() == MembershipList.Status.DEAD);
    if (others.isEmpty()) {
      return "";
    }
    Collections.shuffle(others, new Random(helpers.nextLong()));
    order.addAll(others);
    return order.removeFirst();
  }

  private long roundTripMicros(String from, String to, long nowMicros) {
    if (isCrashed(to, nowMicros)) {
      return Long.MAX_VALUE / 2;
    }
    return oneWayMicros(from, to) + oneWayMicros(to, from);
  }

  private long oneWayMicros(String from, String to) {
    NetworkConditions link =
        isDegraded(from, to)
            ? NetworkConditions.onSale().withLoss(DEGRADED_LOSS)
            : NetworkConditions.onSale();
    // A lost message is a message that never arrives: an infinite delay, in effect.
    long delayMicros = link.sampleDelayMicros(network);
    return link.isLost(network) ? Long.MAX_VALUE / 4 : delayMicros;
  }

  private static boolean isDegraded(String from, String to) {
    return (from.equals(DEGRADED_END_A) && to.equals(DEGRADED_END_B))
        || (from.equals(DEGRADED_END_B) && to.equals(DEGRADED_END_A));
  }

  private static boolean isCrashed(String nodeId, long nowMicros) {
    return nodeId.equals(CRASHED_NODE) && nowMicros >= CRASH_MICROS;
  }

  private static double secondsAfterCrash(long atMicros) {
    return atMicros < 0 ? -1.0 : (atMicros - CRASH_MICROS) / (double) MICROS_PER_SECOND;
  }

  private void trace(
      long nowMicros,
      String prober,
      String target,
      List<String> chosen,
      String helper,
      long pathMicros) {
    if (!isTracing
        || hasTraced
        || !(prober.equals(DEGRADED_END_A) && target.equals(DEGRADED_END_B))) {
      return;
    }
    hasTraced = true;
    double seconds = nowMicros / (double) MICROS_PER_SECOND;
    System.out.println(
        String.format(
            Locale.ROOT,
            "trace, %s: t=%.3f s %s pings %s; no ack within %d ms",
            policy,
            seconds,
            prober,
            target,
            PROBE_TIMEOUT_MICROS / MICROS_PER_MILLI));
    System.out.println(
        String.format(
            Locale.ROOT,
            "trace, %s: t=%.3f s %s asks %s to ping %s",
            policy,
            seconds + PROBE_TIMEOUT_MICROS / (double) MICROS_PER_SECOND,
            prober,
            String.join(", ", chosen),
            target));
    System.out.println(
        String.format(
            Locale.ROOT,
            "trace, %s: t=%.3f s ack from %s through %s (%d ms): %s stays ALIVE, nothing is"
                + " gossiped",
            policy,
            seconds + (PROBE_TIMEOUT_MICROS + pathMicros) / (double) MICROS_PER_SECOND,
            target,
            helper,
            pathMicros / MICROS_PER_MILLI,
            target));
  }
}
