package io.plainround.seatwave.membership;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.stream.Stream;

/**
 * SWIM-style membership among simulated SeatWave nodes where every node keeps its own {@link
 * MembershipList}, and everything a node learns arrives as a message over the simulated network:
 * probes and acknowledgements, indirect probe requests, gossip of suspicions, refutations and
 * confirmations, and full-state push-pull exchanges. The rules follow HashiCorp memberlist and
 * Serf: probe one member per interval round-robin, ask {@code indirectProbes} others when the
 * direct ack is late, suspect when all fail, confirm after the suspicion timeout, gossip news a
 * bounded number of times only while there is news, exchange full state with one live member every
 * 30 s, and try one dead member every 30 s (Serf's reconnect). A node that hears it is suspected or
 * dead raises its own incarnation and gossips ALIVE; no other node may raise it. A partition drops
 * every message that crosses it, so each side can be seen to suspect and remove the other. The
 * {@link ViewModel#SHARED} model replays the seed's simplification (one list for everybody, news of
 * a suspicion reaching the suspect after one probe interval whatever the network does) so the two
 * can be compared. Seeded and on a virtual clock; one instance runs one scenario and is confined to
 * one thread.
 */
final class MembershipCluster {
  private static final long MICROS_PER_MILLI = 1_000;
  private static final long MICROS_PER_SECOND = 1_000_000;
  // HashiCorp memberlist's DefaultLANConfig (config.go): probe 1 s, ack 500 ms, 3 indirect checks,
  // gossip to 3 nodes every 200 ms, SuspicionMult 4, RetransmitMult 4, push-pull every 30 s, and
  // gossip to a dead node for 30 s after its death (GossipToTheDeadTime). Serf's
  // ReconnectInterval is also 30 s.
  private static final long LAN_PROBE_INTERVAL_MICROS = MICROS_PER_SECOND;
  private static final long LAN_PROBE_TIMEOUT_MICROS = 500 * MICROS_PER_MILLI;
  private static final int LAN_INDIRECT_PROBES = 3;
  private static final long LAN_GOSSIP_INTERVAL_MICROS = 200 * MICROS_PER_MILLI;
  private static final int LAN_GOSSIP_FANOUT = 3;
  private static final int SUSPICION_MULTIPLIER = 4;
  private static final int RETRANSMIT_MULTIPLIER = 4;
  private static final long LAN_PUSH_PULL_INTERVAL_MICROS = 30 * MICROS_PER_SECOND;
  private static final long SERF_RECONNECT_INTERVAL_MICROS = 30 * MICROS_PER_SECOND;
  private static final long LAN_GOSSIP_TO_THE_DEAD_MICROS = 30 * MICROS_PER_SECOND;
  // The seed's assumption for the shared view: a suspect hears of it within one probe interval.
  private static final long SHARED_NEWS_DELAY_MICROS = MICROS_PER_SECOND;
  // Assumed sizes: a message header (kind, sender, sequence, target) and one member entry (name,
  // address, incarnation, state), the 40 bytes Section 5e uses.
  static final int HEADER_BYTES = 40;
  static final int ENTRY_BYTES = 40;
  // After the last heal the lists are compared this often, to find when they agree again.
  private static final long OBSERVE_STEP_MICROS = 100 * MICROS_PER_MILLI;
  // Three independent random sources from one seed: the network, the choices, the start phases.
  private static final long CHOICES_SEED_OFFSET = 1;
  private static final long PHASES_SEED_OFFSET = 2;
  // A probe failure is a crossing of the fixed-timeout detector's threshold, level 1 of 1.
  private static final double PROBE_FAILURE_LEVEL = 1.0;

  /** Where each node's view lives. */
  enum ViewModel {
    /** Every node has its own list, and learns only by messages. */
    PER_NODE,
    /** One list for the cluster, as in the seed's {@code SwimProbeCli}. */
    SHARED
  }

  /** What a gossip message carries. */
  enum Dissemination {
    /** Only the news, each item sent a bounded number of times, nothing when there is none. */
    DELTAS,
    /** The sender's whole table, every round. */
    FULL_TABLES
  }

  /** The kinds of message, for the traffic counts. */
  enum MessageKind {
    PING,
    PING_REQ,
    ACK,
    GOSSIP,
    PUSH_PULL
  }

  /**
   * The protocol's settings.
   *
   * @param nodeCount nodes n1 to nN
   * @param probeIntervalMicros time between two probes by one node
   * @param probeTimeoutMicros wait for the direct ack before asking helpers
   * @param indirectProbes helpers asked when the direct ack is late
   * @param suspicionTimeoutMicros how long a suspicion must stand before a list confirms it
   * @param gossipIntervalMicros time between two gossip rounds of one node
   * @param gossipFanout peers per gossip round
   * @param retransmitLimit messages that carry one item of news from one node
   * @param pushPullIntervalMicros time between two full-state exchanges with a live member
   * @param reconnectIntervalMicros time between two attempts to reach a dead member
   * @param gossipToTheDeadMicros how long a dead member still receives gossip
   * @param network delay and loss of every link
   * @param viewModel per-node lists or one shared list
   * @param dissemination deltas or full tables
   * @param seed the seed of every random draw
   */
  record Settings(
      int nodeCount,
      long probeIntervalMicros,
      long probeTimeoutMicros,
      int indirectProbes,
      long suspicionTimeoutMicros,
      long gossipIntervalMicros,
      int gossipFanout,
      int retransmitLimit,
      long pushPullIntervalMicros,
      long reconnectIntervalMicros,
      long gossipToTheDeadMicros,
      NetworkConditions network,
      ViewModel viewModel,
      Dissemination dissemination,
      long seed) {
    Settings {
      Objects.requireNonNull(network, "network");
      Objects.requireNonNull(viewModel, "viewModel");
      Objects.requireNonNull(dissemination, "dissemination");
      if (nodeCount < 2 || indirectProbes < 0 || gossipFanout < 1 || retransmitLimit < 1) {
        throw new IllegalArgumentException(
            "need nodeCount >= 2, indirectProbes >= 0, gossipFanout >= 1, retransmitLimit >= 1");
      }
      if (probeTimeoutMicros <= 0 || probeTimeoutMicros >= probeIntervalMicros) {
        throw new IllegalArgumentException("need 0 < probeTimeoutMicros < probeIntervalMicros");
      }
      if (suspicionTimeoutMicros < 0
          || gossipIntervalMicros <= 0
          || pushPullIntervalMicros <= 0
          || reconnectIntervalMicros <= 0
          || gossipToTheDeadMicros < 0) {
        throw new IllegalArgumentException("intervals must be positive and timeouts not negative");
      }
    }

    /**
     * memberlist's LAN defaults for {@code nodeCount} nodes on the on-sale network: the suspicion
     * timeout is 4 x max(1, log10 N) probe intervals (4 s at 10 nodes, 12 s at 1,000) and the
     * retransmit limit 4 x ceil(log10(N + 1)) (8 at 10 nodes, 16 at 1,000).
     */
    static Settings memberlistLan(int nodeCount, long seed) {
      double scale = Math.max(1.0, Math.log10(nodeCount));
      int retransmits = RETRANSMIT_MULTIPLIER * (int) Math.ceil(Math.log10(nodeCount + 1.0));
      return new Settings(
          nodeCount,
          LAN_PROBE_INTERVAL_MICROS,
          LAN_PROBE_TIMEOUT_MICROS,
          LAN_INDIRECT_PROBES,
          Math.round(SUSPICION_MULTIPLIER * scale * LAN_PROBE_INTERVAL_MICROS),
          LAN_GOSSIP_INTERVAL_MICROS,
          LAN_GOSSIP_FANOUT,
          retransmits,
          LAN_PUSH_PULL_INTERVAL_MICROS,
          SERF_RECONNECT_INTERVAL_MICROS,
          LAN_GOSSIP_TO_THE_DEAD_MICROS,
          NetworkConditions.onSale(),
          ViewModel.PER_NODE,
          Dissemination.DELTAS,
          seed);
    }

    Settings withViewModel(ViewModel other) {
      return new Settings(
          nodeCount,
          probeIntervalMicros,
          probeTimeoutMicros,
          indirectProbes,
          suspicionTimeoutMicros,
          gossipIntervalMicros,
          gossipFanout,
          retransmitLimit,
          pushPullIntervalMicros,
          reconnectIntervalMicros,
          gossipToTheDeadMicros,
          network,
          other,
          dissemination,
          seed);
    }

    Settings withDissemination(Dissemination other) {
      return new Settings(
          nodeCount,
          probeIntervalMicros,
          probeTimeoutMicros,
          indirectProbes,
          suspicionTimeoutMicros,
          gossipIntervalMicros,
          gossipFanout,
          retransmitLimit,
          pushPullIntervalMicros,
          reconnectIntervalMicros,
          gossipToTheDeadMicros,
          network,
          viewModel,
          other,
          seed);
    }

    Settings withSeed(long other) {
      return new Settings(
          nodeCount,
          probeIntervalMicros,
          probeTimeoutMicros,
          indirectProbes,
          suspicionTimeoutMicros,
          gossipIntervalMicros,
          gossipFanout,
          retransmitLimit,
          pushPullIntervalMicros,
          reconnectIntervalMicros,
          gossipToTheDeadMicros,
          network,
          viewModel,
          dissemination,
          other);
    }
  }

  /** A node stops for good at {@code atMicros}: it sends, answers and decides nothing after it. */
  record Crash(String nodeId, long atMicros) {
    Crash {
      Objects.requireNonNull(nodeId, "nodeId");
      if (atMicros < 0) {
        throw new IllegalArgumentException("atMicros must not be negative");
      }
    }
  }

  /** From {@code fromMicros} until {@code untilMicros}, no message crosses between the sides. */
  record Partition(Set<String> sideA, long fromMicros, long untilMicros) {
    Partition {
      sideA = Set.copyOf(sideA);
      if (sideA.isEmpty() || fromMicros < 0 || untilMicros <= fromMicros) {
        throw new IllegalArgumentException("need a non-empty side and 0 <= from < until");
      }
    }

    boolean separates(String first, String second, long nowMicros) {
      return nowMicros >= fromMicros
          && nowMicros < untilMicros
          && sideA.contains(first) != sideA.contains(second);
    }
  }

  /** What happens in one run, and for how long it runs. */
  record Scenario(long runMicros, List<Crash> crashes, List<Partition> partitions) {
    Scenario {
      crashes = List.copyOf(crashes);
      partitions = List.copyOf(partitions);
      if (runMicros <= 0) {
        throw new IllegalArgumentException("runMicros must be positive");
      }
    }
  }

  /** One change that a node's list applied: who saw what about whom, and when. */
  record Change(
      long atMicros,
      String observer,
      String subject,
      MembershipList.Status status,
      long incarnation) {}

  /** A node raised an incarnation to answer an accusation; only the subject may be the refuter. */
  record Refutation(long atMicros, String refuter, String subject, long incarnation) {}

  /**
   * Everything one run produced.
   *
   * @param nodeIds n1 to nN
   * @param changes every applied change, in time order
   * @param refutations every raised incarnation, in time order
   * @param messages messages sent, by kind (lost and cut ones included)
   * @param bytesSent bytes of every message sent, at the assumed sizes
   * @param staleAliveRefusedForDead ALIVE messages a list refused for a member it holds DEAD
   * @param convergedAtMicros first time after the last heal when all live lists agreed, or -1
   */
  record Result(
      List<String> nodeIds,
      List<Change> changes,
      List<Refutation> refutations,
      Map<MessageKind, Long> messages,
      long bytesSent,
      long staleAliveRefusedForDead,
      long convergedAtMicros) {
    Result {
      nodeIds = List.copyOf(nodeIds);
      changes = List.copyOf(changes);
      refutations = List.copyOf(refutations);
      messages = Map.copyOf(messages);
    }

    long totalMessages() {
      return messages.values().stream().mapToLong(Long::longValue).sum();
    }

    /**
     * The first time, at or after {@code fromMicros}, that one of {@code observers} applied {@code
     * status} about one of {@code subjects}; -1 if none did.
     */
    long firstChange(
        Set<String> observers,
        Set<String> subjects,
        MembershipList.Status status,
        long fromMicros) {
      return matching(observers, subjects, status, fromMicros)
          .mapToLong(Change::atMicros)
          .findFirst()
          .orElse(-1);
    }

    /**
     * The time by which every one of {@code observers} had applied {@code status} about every one
     * of {@code subjects} (other than itself) at or after {@code fromMicros}; -1 if a pair never
     * got there.
     */
    long everyObserverHolds(
        Set<String> observers,
        Set<String> subjects,
        MembershipList.Status status,
        long fromMicros) {
      long latest = -1;
      for (String observer : observers) {
        for (String subject : subjects) {
          if (observer.equals(subject)) {
            continue;
          }
          long first = firstChange(Set.of(observer), Set.of(subject), status, fromMicros);
          if (first < 0) {
            return -1;
          }
          latest = Math.max(latest, first);
        }
      }
      return latest;
    }

    /**
     * How many changes to {@code status} were applied in [{@code fromMicros}, {@code untilMicros}).
     */
    long countChanges(MembershipList.Status status, long fromMicros, long untilMicros) {
      return changes.stream()
          .filter(change -> change.status() == status)
          .filter(change -> change.atMicros() >= fromMicros && change.atMicros() < untilMicros)
          .count();
    }

    private Stream<Change> matching(
        Set<String> observers,
        Set<String> subjects,
        MembershipList.Status status,
        long fromMicros) {
      return changes.stream()
          .filter(change -> change.status() == status && change.atMicros() >= fromMicros)
          .filter(change -> observers.contains(change.observer()))
          .filter(change -> subjects.contains(change.subject()));
    }
  }

  private record Event(long atMicros, long order, Runnable action) {}

  /** How an update reached a node: as news, or inside another node's full state. */
  private enum Source {
    NEWS,
    STATE
  }

  private record Update(
      String nodeId, long incarnation, MembershipList.Status status, String origin) {}

  private record Message(
      MessageKind kind,
      String from,
      String to,
      long probeSequence,
      String target,
      String requester,
      List<Update> updates,
      boolean isReply) {}

  /** One simulated node; confined to the simulation's thread. */
  private static final class Node {
    private final String nodeId;
    private final MembershipList view;
    private final List<String> probeOrder = new ArrayList<>();
    private final List<Update> news = new ArrayList<>();
    private final List<Integer> newsTransmits = new ArrayList<>();
    private long probeSequence;
    private String probeTarget = "";
    private boolean isProbeAcked;

    private Node(String nodeId, MembershipList view) {
      this.nodeId = nodeId;
      this.view = view;
    }
  }

  private final Settings settings;
  private final Scenario scenario;
  private final SplittableRandom network;
  private final SplittableRandom choices;
  private final Map<String, Node> nodes = new LinkedHashMap<>();
  private final Map<String, Long> ownIncarnations = new HashMap<>();
  private final PriorityQueue<Event> events =
      new PriorityQueue<>((first, second) ->
          first.atMicros() != second.atMicros()
              ? Long.compare(first.atMicros(), second.atMicros())
              : Long.compare(first.order(), second.order()));
  private final List<Change> changes = new ArrayList<>();
  private final List<Refutation> refutations = new ArrayList<>();
  private final Map<MessageKind, Long> messages = new EnumMap<>(MessageKind.class);
  private long eventOrder;
  private long bytesSent;
  private long staleAliveRefusedForDead;
  private long convergedAtMicros = -1;

  private MembershipCluster(Settings settings, Scenario scenario) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.scenario = Objects.requireNonNull(scenario, "scenario");
    this.network = new SplittableRandom(settings.seed());
    this.choices = new SplittableRandom(settings.seed() + CHOICES_SEED_OFFSET);
  }

  /** Runs {@code scenario} under {@code settings} and returns what every list did. */
  static Result run(Settings settings, Scenario scenario) {
    return new MembershipCluster(settings, scenario).simulate();
  }

  private Result simulate() {
    createNodes();
    SplittableRandom phases = new SplittableRandom(settings.seed() + PHASES_SEED_OFFSET);
    for (Node node : nodes.values()) {
      schedule(phases.nextLong(settings.probeIntervalMicros()), now -> probeTick(node, now));
      schedule(phases.nextLong(settings.gossipIntervalMicros()), now -> gossipTick(node, now));
      schedule(phases.nextLong(settings.pushPullIntervalMicros()), now -> pushPullTick(node, now));
      schedule(
          phases.nextLong(settings.reconnectIntervalMicros()), now -> reconnectTick(node, now));
    }
    scenario.partitions().stream()
        .mapToLong(Partition::untilMicros)
        .max()
        .ifPresent(healMicros -> schedule(healMicros, this::observe));
    while (!events.isEmpty() && events.peek().atMicros() < scenario.runMicros()) {
      events.poll().action().run();
    }
    return new Result(
        List.copyOf(nodes.keySet()),
        changes,
        refutations,
        messages,
        bytesSent,
        staleAliveRefusedForDead,
        convergedAtMicros);
  }

  private void createNodes() {
    MembershipList shared = new MembershipList(settings.suspicionTimeoutMicros());
    for (int nodeNumber = 1; nodeNumber <= settings.nodeCount(); nodeNumber++) {
      String nodeId = "n" + nodeNumber;
      MembershipList view =
          settings.viewModel() == ViewModel.SHARED
              ? shared
              : new MembershipList(settings.suspicionTimeoutMicros());
      nodes.put(nodeId, new Node(nodeId, view));
      ownIncarnations.put(nodeId, 0L);
    }
    // Every node starts knowing every node ALIVE at incarnation 0, as after a completed join.
    for (Node node : nodes.values()) {
      for (String member : nodes.keySet()) {
        node.view.markAlive(member, 0, 0);
      }
    }
  }

  private interface Action {
    void at(long nowMicros);
  }

  private void schedule(long atMicros, Action action) {
    long when = atMicros;
    events.add(new Event(when, eventOrder++, () -> action.at(when)));
  }

  // Probing: one member per interval, round-robin over a shuffled list, as SWIM does.

  private void probeTick(Node node, long nowMicros) {
    schedule(nowMicros + settings.probeIntervalMicros(), now -> probeTick(node, now));
    if (isCrashed(node.nodeId, nowMicros)) {
      return;
    }
    // The previous probe's period has ended: no ack, direct or indirect, means a suspicion.
    if (!node.probeTarget.isEmpty() && !node.isProbeAcked) {
      suspectAfterFailedProbe(node, node.probeTarget, nowMicros);
    }
    node.probeTarget = nextProbeTarget(node);
    if (node.probeTarget.isEmpty()) {
      return;
    }
    node.probeSequence++;
    node.isProbeAcked = false;
    long sequence = node.probeSequence;
    send(ping(node.nodeId, node.probeTarget, sequence, node.nodeId), nowMicros);
    schedule(nowMicros + settings.probeTimeoutMicros(), now -> askHelpers(node, sequence, now));
  }

  private void askHelpers(Node node, long sequence, long nowMicros) {
    if (isCrashed(node.nodeId, nowMicros) || sequence != node.probeSequence || node.isProbeAcked) {
      return;
    }
    // memberlist asks only members it holds ALIVE, never the target or itself.
    List<String> helpers =
        membersOf(node, MembershipList.Status.ALIVE).stream()
            .filter(nodeId -> !nodeId.equals(node.probeTarget))
            .toList();
    for (String helper : pick(helpers, settings.indirectProbes())) {
      send(
          new Message(
              MessageKind.PING_REQ,
              node.nodeId,
              helper,
              sequence,
              node.probeTarget,
              node.nodeId,
              List.of(),
              false),
          nowMicros);
    }
  }

  private void suspectAfterFailedProbe(Node node, String target, long nowMicros) {
    MembershipList.Member known = node.view.member(target).orElseThrow();
    if (known.status() == MembershipList.Status.DEAD) {
      return;
    }
    MembershipList.Suspicion evidence =
        new MembershipList.Suspicion(node.nodeId, PROBE_FAILURE_LEVEL, PROBE_FAILURE_LEVEL);
    MembershipList.Outcome outcome =
        node.view.suspect(target, known.incarnation(), evidence, nowMicros);
    if (outcome instanceof MembershipList.Applied applied) {
      afterApplied(node, applied.member(), node.nodeId, nowMicros);
    }
  }

  private String nextProbeTarget(Node node) {
    while (!node.probeOrder.isEmpty()) {
      String candidate = node.probeOrder.removeFirst();
      if (node.view.member(candidate).orElseThrow().status() != MembershipList.Status.DEAD) {
        return candidate;
      }
    }
    List<String> others = new ArrayList<>(membersOf(node, MembershipList.Status.ALIVE));
    others.addAll(membersOf(node, MembershipList.Status.SUSPECT));
    if (others.isEmpty()) {
      return "";
    }
    Collections.sort(others);
    Collections.shuffle(others, choices);
    node.probeOrder.addAll(others);
    return node.probeOrder.removeFirst();
  }

  // Delivery: every message crosses the network, or a partition drops it.

  private void send(Message message, long nowMicros) {
    messages.merge(message.kind(), 1L, Long::sum);
    bytesSent += HEADER_BYTES + (long) ENTRY_BYTES * message.updates().size();
    // Both draws are made for every message, so a cut does not shift the draws of the others.
    long delayMicros = settings.network().sampleDelayMicros(network);
    boolean isLost = settings.network().isLost(network);
    boolean isCut =
        scenario.partitions().stream()
            .anyMatch(partition -> partition.separates(message.from(), message.to(), nowMicros));
    if (isLost || isCut) {
      return;
    }
    schedule(nowMicros + delayMicros, now -> deliver(message, now));
  }

  private void deliver(Message message, long nowMicros) {
    if (isCrashed(message.to(), nowMicros)) {
      return;
    }
    Node node = nodes.get(message.to());
    switch (message.kind()) {
      case PING ->
          send(
              new Message(
                  MessageKind.ACK,
                  node.nodeId,
                  message.from(),
                  message.probeSequence(),
                  node.nodeId,
                  message.requester(),
                  List.of(),
                  false),
              nowMicros);
      case PING_REQ ->
          send(
              ping(node.nodeId, message.target(), message.probeSequence(), message.from()),
              nowMicros);
      case ACK -> receiveAck(node, message, nowMicros);
      case GOSSIP -> {
        boolean isTable = settings.dissemination() == Dissemination.FULL_TABLES;
        message
            .updates()
            .forEach(
                update -> receive(node, update, isTable ? Source.STATE : Source.NEWS, nowMicros));
      }
      case PUSH_PULL -> {
        if (!message.isReply()) {
          send(
              new Message(
                  MessageKind.PUSH_PULL, node.nodeId, message.from(), 0, "", "", table(node), true),
              nowMicros);
        }
        message.updates().forEach(update -> receive(node, update, Source.STATE, nowMicros));
      }
    }
  }

  private void receiveAck(Node node, Message message, long nowMicros) {
    if (!message.requester().equals(node.nodeId)) {
      // A helper passes the target's ack on to the node that asked.
      send(
          new Message(
              MessageKind.ACK,
              node.nodeId,
              message.requester(),
              message.probeSequence(),
              message.target(),
              message.requester(),
              List.of(),
              false),
          nowMicros);
      return;
    }
    if (message.probeSequence() == node.probeSequence) {
      node.isProbeAcked = true;
    }
  }

  // News: what a list does with an update, and how it spreads.

  private void receive(Node node, Update update, Source source, long nowMicros) {
    boolean isAboutMe = update.nodeId().equals(node.nodeId);
    if (isAboutMe) {
      if (update.status() != MembershipList.Status.ALIVE
          && update.incarnation() >= ownIncarnations.get(node.nodeId)) {
        refute(node, update.nodeId(), update.incarnation(), nowMicros);
      }
      return;
    }
    // memberlist's mergeState: a DEAD entry in another node's full state is taken as a suspicion,
    // so that a state exchange alone never removes a member.
    MembershipList.Status status =
        source == Source.STATE && update.status() == MembershipList.Status.DEAD
            ? MembershipList.Status.SUSPECT
            : update.status();
    MembershipList.Outcome outcome =
        switch (status) {
          case ALIVE -> markAlive(node, update, nowMicros);
          case SUSPECT ->
              node.view.suspect(
                  update.nodeId(),
                  update.incarnation(),
                  new MembershipList.Suspicion(
                      update.origin(), PROBE_FAILURE_LEVEL, PROBE_FAILURE_LEVEL),
                  nowMicros);
          case DEAD -> node.view.learnDead(update.nodeId(), update.incarnation(), nowMicros);
        };
    if (outcome instanceof MembershipList.Applied applied) {
      afterApplied(node, applied.member(), update.origin(), nowMicros);
    }
  }

  private MembershipList.Outcome markAlive(Node node, Update update, long nowMicros) {
    MembershipList.Outcome outcome =
        node.view.markAlive(update.nodeId(), update.incarnation(), nowMicros);
    boolean wasDead =
        node.view.member(update.nodeId()).orElseThrow().status() == MembershipList.Status.DEAD;
    if (wasDead && outcome instanceof MembershipList.Refused) {
      staleAliveRefusedForDead++;
    }
    return outcome;
  }

  private void refute(Node refuter, String subject, long accusedIncarnation, long nowMicros) {
    long raised = Math.max(ownIncarnations.get(subject), accusedIncarnation) + 1;
    ownIncarnations.put(subject, raised);
    refutations.add(new Refutation(nowMicros, refuter.nodeId, subject, raised));
    MembershipList.Outcome outcome = refuter.view.markAlive(subject, raised, nowMicros);
    if (outcome instanceof MembershipList.Applied applied) {
      afterApplied(refuter, applied.member(), refuter.nodeId, nowMicros);
    } else {
      spread(refuter, new Update(subject, raised, MembershipList.Status.ALIVE, refuter.nodeId));
    }
  }

  private void afterApplied(
      Node node, MembershipList.Member member, String origin, long nowMicros) {
    changes.add(
        new Change(nowMicros, node.nodeId, member.nodeId(), member.status(), member.incarnation()));
    if (member.status() == MembershipList.Status.SUSPECT) {
      String subject = member.nodeId();
      schedule(
          nowMicros + settings.suspicionTimeoutMicros(),
          now -> confirmSuspicion(node, subject, now));
    }
    if (settings.viewModel() == ViewModel.SHARED) {
      if (member.status() != MembershipList.Status.ALIVE) {
        String subject = member.nodeId();
        schedule(nowMicros + SHARED_NEWS_DELAY_MICROS, now -> hearSharedNews(subject, now));
      }
      return;
    }
    spread(node, new Update(member.nodeId(), member.incarnation(), member.status(), origin));
  }

  private void confirmSuspicion(Node node, String subject, long nowMicros) {
    if (isCrashed(node.nodeId, nowMicros)) {
      return;
    }
    if (node.view.confirmDead(subject, nowMicros) instanceof MembershipList.Applied applied) {
      afterApplied(node, applied.member(), node.nodeId, nowMicros);
    }
  }

  private void hearSharedNews(String subject, long nowMicros) {
    // The seed's simplification: the suspect hears through "gossip" whatever the network does.
    if (isCrashed(subject, nowMicros)) {
      return;
    }
    Node node = nodes.get(subject);
    MembershipList.Member known = node.view.member(subject).orElseThrow();
    if (known.status() != MembershipList.Status.ALIVE) {
      refute(node, subject, known.incarnation(), nowMicros);
    }
  }

  private void spread(Node node, Update update) {
    if (settings.dissemination() == Dissemination.FULL_TABLES) {
      return;
    }
    // Newer news about a member replaces older news about it, as memberlist's queue does.
    for (int index = node.news.size() - 1; index >= 0; index--) {
      if (node.news.get(index).nodeId().equals(update.nodeId())) {
        node.news.remove(index);
        node.newsTransmits.remove(index);
      }
    }
    node.news.add(update);
    node.newsTransmits.add(0);
  }

  private void gossipTick(Node node, long nowMicros) {
    schedule(nowMicros + settings.gossipIntervalMicros(), now -> gossipTick(node, now));
    if (isCrashed(node.nodeId, nowMicros) || settings.viewModel() == ViewModel.SHARED) {
      return;
    }
    List<String> peers =
        node.view.members().stream()
            .filter(member -> !member.nodeId().equals(node.nodeId))
            .filter(member ->
                member.status() != MembershipList.Status.DEAD
                    || nowMicros - member.sinceMicros() < settings.gossipToTheDeadMicros())
            .map(MembershipList.Member::nodeId)
            .toList();
    for (String peer : pick(peers, settings.gossipFanout())) {
      List<Update> carried =
          settings.dissemination() == Dissemination.FULL_TABLES ? table(node) : takeNews(node);
      if (carried.isEmpty()) {
        // memberlist sends no gossip message when it has nothing to say.
        return;
      }
      send(
          new Message(MessageKind.GOSSIP, node.nodeId, peer, 0, "", "", carried, false), nowMicros);
    }
  }

  private List<Update> takeNews(Node node) {
    List<Update> carried = List.copyOf(node.news);
    for (int index = node.news.size() - 1; index >= 0; index--) {
      int transmits = node.newsTransmits.get(index) + 1;
      if (transmits >= settings.retransmitLimit()) {
        node.news.remove(index);
        node.newsTransmits.remove(index);
      } else {
        node.newsTransmits.set(index, transmits);
      }
    }
    return carried;
  }

  private void pushPullTick(Node node, long nowMicros) {
    schedule(nowMicros + settings.pushPullIntervalMicros(), now -> pushPullTick(node, now));
    exchangeState(node, membersOf(node, MembershipList.Status.ALIVE), nowMicros);
  }

  private void reconnectTick(Node node, long nowMicros) {
    schedule(nowMicros + settings.reconnectIntervalMicros(), now -> reconnectTick(node, now));
    // Serf tries to rejoin one random failed member per interval; a join is a state exchange.
    exchangeState(node, membersOf(node, MembershipList.Status.DEAD), nowMicros);
  }

  private void exchangeState(Node node, List<String> candidates, long nowMicros) {
    if (isCrashed(node.nodeId, nowMicros) || settings.viewModel() == ViewModel.SHARED) {
      return;
    }
    for (String peer : pick(candidates, 1)) {
      send(
          new Message(MessageKind.PUSH_PULL, node.nodeId, peer, 0, "", "", table(node), false),
          nowMicros);
    }
  }

  private List<Update> table(Node node) {
    return node.view.members().stream()
        .map(member ->
            new Update(member.nodeId(), member.incarnation(), member.status(), node.nodeId))
        .toList();
  }

  // Convergence: after the last heal, compare every live list until all agree.

  private void observe(long nowMicros) {
    if (haveConverged(nowMicros)) {
      convergedAtMicros = nowMicros;
      return;
    }
    schedule(nowMicros + OBSERVE_STEP_MICROS, this::observe);
  }

  private boolean haveConverged(long nowMicros) {
    List<List<String>> views = new ArrayList<>();
    for (Node node : nodes.values()) {
      if (isCrashed(node.nodeId, nowMicros)) {
        continue;
      }
      List<String> entries = new ArrayList<>();
      for (MembershipList.Member member : node.view.members()) {
        MembershipList.Status expected =
            isCrashed(member.nodeId(), nowMicros)
                ? MembershipList.Status.DEAD
                : MembershipList.Status.ALIVE;
        if (member.status() != expected) {
          return false;
        }
        entries.add(member.nodeId() + "@" + member.incarnation() + member.status());
      }
      views.add(entries);
    }
    return views.stream().distinct().count() <= 1;
  }

  // Helpers.

  private List<String> membersOf(Node node, MembershipList.Status status) {
    return node.view.members().stream()
        .filter(member -> member.status() == status && !member.nodeId().equals(node.nodeId))
        .map(MembershipList.Member::nodeId)
        .toList();
  }

  private List<String> pick(List<String> candidates, int count) {
    List<String> shuffled = new ArrayList<>(candidates);
    Collections.shuffle(shuffled, choices);
    return List.copyOf(shuffled.subList(0, Math.min(count, shuffled.size())));
  }

  private boolean isCrashed(String nodeId, long nowMicros) {
    return scenario.crashes().stream()
        .anyMatch(crash -> crash.nodeId().equals(nodeId) && nowMicros >= crash.atMicros());
  }

  private static Message ping(String from, String to, long sequence, String requester) {
    return new Message(MessageKind.PING, from, to, sequence, to, requester, List.of(), false);
  }
}
