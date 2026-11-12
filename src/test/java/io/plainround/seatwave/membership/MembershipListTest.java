package io.plainround.seatwave.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/**
 * The module's invariant: a node is removed only after a detector crossed its threshold and the
 * suspicion stood for the suspicion timeout, and a removed node comes back only with a higher
 * incarnation than the one it was removed with.
 */
class MembershipListTest {
  private static final long SUSPICION_TIMEOUT_MICROS = 4_000_000;
  private static final long SUSPECTED_AT_MICROS = 10_000_000;
  private static final long TOO_EARLY_MICROS = SUSPECTED_AT_MICROS + SUSPICION_TIMEOUT_MICROS - 1;
  private static final long ON_TIME_MICROS = SUSPECTED_AT_MICROS + SUSPICION_TIMEOUT_MICROS;
  private static final long LATER_MICROS = ON_TIME_MICROS + 1_000_000;
  private static final long REMOVED_INCARNATION = 5;
  private static final int STALE_MESSAGES = 1_000;
  private static final long SHUFFLE_SEED = 2_404;
  private static final double PHI_THRESHOLD = 8.0;
  private static final double PHI_ABOVE_THRESHOLD = 9.3;
  private static final double PHI_BELOW_THRESHOLD = 7.9;

  private final MembershipList membership = new MembershipList(SUSPICION_TIMEOUT_MICROS);

  @Test
  void rejectsRejoinWithTheIncarnationTheNodeWasRemovedWith() {
    removeNodeAtIncarnation("n4", REMOVED_INCARNATION);

    MembershipList.Outcome outcome = membership.markAlive("n4", REMOVED_INCARNATION, LATER_MICROS);

    assertThat(outcome)
        .isEqualTo(new MembershipList.Refused("n4", MembershipList.Reason.STALE_INCARNATION));
    assertThat(membership.member("n4").orElseThrow().status())
        .isEqualTo(MembershipList.Status.DEAD);
  }

  @Test
  void acceptsRejoinWithAHigherIncarnation() {
    removeNodeAtIncarnation("n4", REMOVED_INCARNATION);

    MembershipList.Outcome outcome =
        membership.markAlive("n4", REMOVED_INCARNATION + 1, LATER_MICROS);

    assertThat(outcome).isInstanceOf(MembershipList.Applied.class);
    assertThat(membership.member("n4").orElseThrow().status())
        .isEqualTo(MembershipList.Status.ALIVE);
  }

  @Test
  void staleAliveMessagesInAnyOrderNeverBringBackARemovedNode() {
    removeNodeAtIncarnation("n4", REMOVED_INCARNATION);
    List<Long> staleIncarnations = new ArrayList<>();
    for (int message = 0; message < STALE_MESSAGES; message++) {
      staleIncarnations.add(message % (REMOVED_INCARNATION + 1));
    }
    Collections.shuffle(staleIncarnations, new Random(SHUFFLE_SEED));

    long accepted =
        staleIncarnations.stream()
            .map(incarnation -> membership.markAlive("n4", incarnation, LATER_MICROS))
            .filter(outcome -> outcome instanceof MembershipList.Applied)
            .count();

    assertThat(accepted).isZero();
    assertThat(membership.member("n4").orElseThrow().incarnation()).isEqualTo(REMOVED_INCARNATION);
  }

  @Test
  void refusesRemovalBeforeTheSuspicionTimeout() {
    membership.markAlive("n4", 0, 0);
    membership.suspect("n4", 0, evidence(), SUSPECTED_AT_MICROS);

    MembershipList.Outcome outcome = membership.confirmDead("n4", TOO_EARLY_MICROS);

    assertThat(outcome)
        .isEqualTo(new MembershipList.Refused("n4", MembershipList.Reason.SUSPICION_TOO_YOUNG));
  }

  @Test
  void refusesRemovalOfANodeThatIsNotSuspected() {
    membership.markAlive("n4", 0, 0);

    MembershipList.Outcome outcome = membership.confirmDead("n4", LATER_MICROS);

    assertThat(outcome)
        .isEqualTo(new MembershipList.Refused("n4", MembershipList.Reason.NOT_SUSPECTED));
  }

  @Test
  void refutationWithAHigherIncarnationCancelsTheSuspicionSoNoRemovalFollows() {
    membership.markAlive("n4", 0, 0);
    membership.suspect("n4", 0, evidence(), SUSPECTED_AT_MICROS);
    membership.markAlive("n4", 1, SUSPECTED_AT_MICROS + 1);

    MembershipList.Outcome outcome = membership.confirmDead("n4", LATER_MICROS);

    assertThat(outcome)
        .isEqualTo(new MembershipList.Refused("n4", MembershipList.Reason.NOT_SUSPECTED));
  }

  @Test
  void aliveWithTheSuspectedIncarnationDoesNotCancelTheSuspicion() {
    membership.markAlive("n4", 0, 0);
    membership.suspect("n4", 0, evidence(), SUSPECTED_AT_MICROS);

    MembershipList.Outcome outcome = membership.markAlive("n4", 0, SUSPECTED_AT_MICROS + 1);

    assertThat(outcome)
        .isEqualTo(new MembershipList.Refused("n4", MembershipList.Reason.STALE_INCARNATION));
    assertThat(membership.member("n4").orElseThrow().status())
        .isEqualTo(MembershipList.Status.SUSPECT);
  }

  @Test
  void olderAliveMessagesNeverReviveADeadEntry() {
    removeNodeAtIncarnation("n4", REMOVED_INCARNATION);

    long accepted =
        LongStream.range(0, REMOVED_INCARNATION)
            .mapToObj(incarnation -> membership.markAlive("n4", incarnation, LATER_MICROS))
            .filter(outcome -> outcome instanceof MembershipList.Applied)
            .count();

    assertThat(accepted).isZero();
    assertThat(membership.member("n4").orElseThrow().status())
        .isEqualTo(MembershipList.Status.DEAD);
  }

  @Test
  void repeatingASuspicionDoesNotRestartItsTimer() {
    membership.markAlive("n4", 0, 0);
    membership.suspect("n4", 0, evidence(), SUSPECTED_AT_MICROS);
    membership.suspect("n4", 0, evidence(), SUSPECTED_AT_MICROS + 1);

    MembershipList.Outcome outcome = membership.confirmDead("n4", ON_TIME_MICROS);

    assertThat(outcome).isInstanceOf(MembershipList.Applied.class);
  }

  @Test
  void learnsADeathConfirmedElsewhereAtTheKnownIncarnation() {
    membership.markAlive("n4", REMOVED_INCARNATION, 0);

    MembershipList.Outcome outcome = membership.learnDead("n4", REMOVED_INCARNATION, LATER_MICROS);

    assertThat(outcome).isInstanceOf(MembershipList.Applied.class);
    assertThat(membership.member("n4").orElseThrow().status())
        .isEqualTo(MembershipList.Status.DEAD);
  }

  @Test
  void refusesADeathReportOlderThanTheNodesRefutation() {
    membership.markAlive("n4", REMOVED_INCARNATION, 0);

    MembershipList.Outcome outcome =
        membership.learnDead("n4", REMOVED_INCARNATION - 1, LATER_MICROS);

    assertThat(outcome)
        .isEqualTo(new MembershipList.Refused("n4", MembershipList.Reason.STALE_INCARNATION));
  }

  @Test
  void refusesUpdatesAboutAnUnknownOrAlreadyDeadNode() {
    removeNodeAtIncarnation("n4", REMOVED_INCARNATION);

    assertThat(membership.learnDead("n9", 0, LATER_MICROS))
        .isEqualTo(new MembershipList.Refused("n9", MembershipList.Reason.UNKNOWN_MEMBER));
    assertThat(membership.suspect("n9", 0, evidence(), LATER_MICROS))
        .isEqualTo(new MembershipList.Refused("n9", MembershipList.Reason.UNKNOWN_MEMBER));
    assertThat(membership.confirmDead("n9", LATER_MICROS))
        .isEqualTo(new MembershipList.Refused("n9", MembershipList.Reason.UNKNOWN_MEMBER));
    assertThat(membership.learnDead("n4", REMOVED_INCARNATION, LATER_MICROS))
        .isEqualTo(new MembershipList.Refused("n4", MembershipList.Reason.ALREADY_DEAD));
    assertThat(membership.suspect("n4", REMOVED_INCARNATION, evidence(), LATER_MICROS))
        .isEqualTo(new MembershipList.Refused("n4", MembershipList.Reason.ALREADY_DEAD));
  }

  @Test
  void rejectsInvalidMembersAndTimeouts() {
    assertThatThrownBy(() -> new MembershipList(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("suspicionTimeoutMicros");
    assertThatThrownBy(() -> new MembershipList.Member("n1", -1, MembershipList.Status.ALIVE, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("incarnation");
  }

  @Test
  void suspicionEvidenceCannotBeBuiltBelowTheThreshold() {
    assertThatThrownBy(() -> new MembershipList.Suspicion("n1", PHI_BELOW_THRESHOLD, PHI_THRESHOLD))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("below the threshold");
  }

  private void removeNodeAtIncarnation(String nodeId, long incarnation) {
    membership.markAlive(nodeId, incarnation, 0);
    membership.suspect(nodeId, incarnation, evidence(), SUSPECTED_AT_MICROS);
    assertThat(membership.confirmDead(nodeId, ON_TIME_MICROS))
        .isInstanceOf(MembershipList.Applied.class);
  }

  private static MembershipList.Suspicion evidence() {
    return new MembershipList.Suspicion("n1", PHI_ABOVE_THRESHOLD, PHI_THRESHOLD);
  }
}
