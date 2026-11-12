package io.plainround.seatwave.membership;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Which SeatWave nodes are members, and in what state, with SWIM's rules for incarnation numbers.
 * It is the only way to change membership, and it enforces the module's invariant: a node is
 * removed only after a detector's threshold was crossed (a suspicion, which needs evidence of the
 * crossing) and the suspicion has stood unrefuted for the suspicion timeout; and a removed node
 * comes back only with a higher incarnation than the one it was removed with, so a late or repeated
 * "alive" message about the old incarnation is refused. Not thread-safe: confined to the node's
 * membership thread.
 */
final class MembershipList {

  /** A member's state as this list knows it. */
  enum Status {
    ALIVE,
    SUSPECT,
    DEAD
  }

  /**
   * One member.
   *
   * @param nodeId the node's name
   * @param incarnation the node's own counter; only the node itself raises it, to refute
   * @param status its state
   * @param sinceMicros when it entered that state
   */
  record Member(String nodeId, long incarnation, Status status, long sinceMicros) {
    Member {
      Objects.requireNonNull(nodeId, "nodeId");
      Objects.requireNonNull(status, "status");
      if (incarnation < 0) {
        throw new IllegalArgumentException("incarnation must not be negative");
      }
    }
  }

  /**
   * Proof that a failure detector crossed its threshold; it cannot be built below the threshold.
   *
   * @param suspectedBy the node whose detector crossed it
   * @param level the detector's suspicion level at that moment
   * @param threshold the detector's threshold
   */
  record Suspicion(String suspectedBy, double level, double threshold) {
    Suspicion {
      Objects.requireNonNull(suspectedBy, "suspectedBy");
      if (!(level >= threshold)) {
        throw new IllegalArgumentException(
            "no suspicion below the threshold: level " + level + " < " + threshold);
      }
    }
  }

  /** Why an update was refused. */
  enum Reason {
    STALE_INCARNATION,
    UNKNOWN_MEMBER,
    NOT_SUSPECTED,
    SUSPICION_TOO_YOUNG,
    ALREADY_DEAD
  }

  /** The result of one update. */
  sealed interface Outcome permits Applied, Refused {}

  /** The update changed the list; {@code member} is the new entry. */
  record Applied(Member member) implements Outcome {}

  /** The update was refused and changed nothing. */
  record Refused(String nodeId, Reason reason) implements Outcome {}

  private final long suspicionTimeoutMicros;
  private final Map<String, Member> members = new TreeMap<>();

  /**
   * A list that removes a suspected node after {@code suspicionTimeoutMicros} without refutation.
   * Zero means removal right after suspicion, which is failing open; the simulator uses it only to
   * show the cost.
   */
  MembershipList(long suspicionTimeoutMicros) {
    if (suspicionTimeoutMicros < 0) {
      throw new IllegalArgumentException("suspicionTimeoutMicros must not be negative");
    }
    this.suspicionTimeoutMicros = suspicionTimeoutMicros;
  }

  /**
   * A node announces it is alive, as a join or as a refutation. Applied for an unknown node, or
   * with an incarnation higher than the one known in any state, including DEAD (a rejoin). Refused
   * as stale otherwise, so an old "alive" can neither cancel a newer suspicion nor bring back a
   * removed node.
   */
  Outcome markAlive(String nodeId, long incarnation, long nowMicros) {
    Optional<Member> known = member(nodeId);
    if (known.isPresent() && incarnation <= known.get().incarnation()) {
      return new Refused(nodeId, Reason.STALE_INCARNATION);
    }
    return put(new Member(nodeId, incarnation, Status.ALIVE, nowMicros));
  }

  /**
   * A detector crossed its threshold for the node at {@code incarnation}. Applied when the node is
   * ALIVE at that incarnation or lower, or SUSPECT at a lower one; refused for a dead node, an
   * unknown node, or an incarnation older than the one known (the node has already refuted).
   */
  Outcome suspect(String nodeId, long incarnation, Suspicion evidence, long nowMicros) {
    Objects.requireNonNull(evidence, "evidence");
    Optional<Member> known = member(nodeId);
    if (known.isEmpty()) {
      return new Refused(nodeId, Reason.UNKNOWN_MEMBER);
    }
    Member current = known.get();
    if (current.status() == Status.DEAD) {
      return new Refused(nodeId, Reason.ALREADY_DEAD);
    }
    boolean isOlder = incarnation < current.incarnation();
    boolean isRepeat = current.status() == Status.SUSPECT && incarnation == current.incarnation();
    if (isOlder || isRepeat) {
      // A repeat keeps the first suspicion's start, so repeating it cannot reset the timer.
      return new Refused(nodeId, Reason.STALE_INCARNATION);
    }
    return put(new Member(nodeId, incarnation, Status.SUSPECT, nowMicros));
  }

  /**
   * Removes a node whose suspicion has stood for the suspicion timeout without refutation. Refused
   * for a node that is not SUSPECT, and for a suspicion younger than the timeout.
   */
  Outcome confirmDead(String nodeId, long nowMicros) {
    Optional<Member> known = member(nodeId);
    if (known.isEmpty()) {
      return new Refused(nodeId, Reason.UNKNOWN_MEMBER);
    }
    Member current = known.get();
    if (current.status() != Status.SUSPECT) {
      return new Refused(nodeId, Reason.NOT_SUSPECTED);
    }
    if (nowMicros - current.sinceMicros() < suspicionTimeoutMicros) {
      return new Refused(nodeId, Reason.SUSPICION_TOO_YOUNG);
    }
    // The dead entry keeps the incarnation, so that only a higher one can rejoin.
    return put(new Member(nodeId, current.incarnation(), Status.DEAD, nowMicros));
  }

  /**
   * Another node reports that its own list confirmed the node dead at {@code incarnation} (its
   * suspicion stood for the timeout there). Applied when the node is known, not already DEAD, and
   * {@code incarnation} is not older than the one known; refused as stale when it is older, because
   * the node has refuted since. Like {@link #confirmDead}, the dead entry keeps the incarnation.
   */
  Outcome learnDead(String nodeId, long incarnation, long nowMicros) {
    Optional<Member> known = member(nodeId);
    if (known.isEmpty()) {
      return new Refused(nodeId, Reason.UNKNOWN_MEMBER);
    }
    Member current = known.get();
    if (current.status() == Status.DEAD) {
      return new Refused(nodeId, Reason.ALREADY_DEAD);
    }
    if (incarnation < current.incarnation()) {
      return new Refused(nodeId, Reason.STALE_INCARNATION);
    }
    return put(new Member(nodeId, incarnation, Status.DEAD, nowMicros));
  }

  /** Every member this list knows, in node-name order, as an unmodifiable snapshot. */
  List<Member> members() {
    return List.copyOf(members.values());
  }

  /** The member's entry, if the node was ever known. */
  Optional<Member> member(String nodeId) {
    return Optional.ofNullable(members.get(Objects.requireNonNull(nodeId, "nodeId")));
  }

  private Outcome put(Member member) {
    members.put(member.nodeId(), member);
    return new Applied(member);
  }
}
