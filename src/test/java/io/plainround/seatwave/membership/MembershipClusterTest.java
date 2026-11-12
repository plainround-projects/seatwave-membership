package io.plainround.seatwave.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The per-node membership simulation: refutation only by the accused node, removal of each side by
 * the other during a cut, convergence within a stated bound after the heal, rejoin only with a
 * higher incarnation, and the same run for the same seed.
 */
class MembershipClusterTest {
  private static final long MICROS_PER_SECOND = 1_000_000;
  private static final int NODES = 10;
  private static final long SEED = 2_404;
  private static final int SEEDS_PER_SPLIT = 5;
  private static final long CUT_FROM_MICROS = 60 * MICROS_PER_SECOND;
  private static final long CUT_UNTIL_MICROS = 120 * MICROS_PER_SECOND;
  private static final long RUN_MICROS = 300 * MICROS_PER_SECOND;
  private static final long CRASH_MICROS = 20 * MICROS_PER_SECOND;
  private static final long CRASH_RUN_MICROS = 60 * MICROS_PER_SECOND;
  // The stated bound: after the heal, one reconnect interval (30 s) for some node on each side to
  // reach a node it holds dead, plus one suspicion timeout (4 s) and one probe interval of slack.
  private static final long CONVERGENCE_BOUND_MICROS = 35 * MICROS_PER_SECOND;

  private final MembershipCluster.Settings settings =
      MembershipCluster.Settings.memberlistLan(NODES, SEED);

  @Test
  void everyRefutationIsMadeByTheAccusedNodeItself() {
    MembershipCluster.Result result = MembershipCluster.run(settings, partition(5));

    assertThat(result.refutations()).isNotEmpty();
    assertThat(result.refutations())
        .allSatisfy(refutation -> assertThat(refutation.refuter()).isEqualTo(refutation.subject()));
  }

  @Test
  void aCrashedNodeIsNeverRefutedAndEndsDeadInEverySurvivorsList() {
    MembershipCluster.Result result =
        MembershipCluster.run(
            settings,
            new MembershipCluster.Scenario(
                CRASH_RUN_MICROS,
                List.of(new MembershipCluster.Crash("n7", CRASH_MICROS)),
                List.of()));
    Set<String> survivors = side(1, NODES);
    survivors.remove("n7");

    assertThat(result.refutations()).noneMatch(refutation -> refutation.subject().equals("n7"));
    assertThat(
            result.everyObserverHolds(
                survivors, Set.of("n7"), MembershipList.Status.DEAD, CRASH_MICROS))
        .isPositive();
  }

  @ParameterizedTest
  @ValueSource(ints = {5, 7})
  void eachSideRemovesEveryNodeOfTheOtherDuringTheCut(int sideSize) {
    MembershipCluster.Result result = MembershipCluster.run(settings, partition(sideSize));
    Set<String> sideA = side(1, sideSize);
    Set<String> sideB = side(sideSize + 1, NODES);

    assertThat(result.everyObserverHolds(sideA, sideB, MembershipList.Status.DEAD, CUT_FROM_MICROS))
        .isBetween(CUT_FROM_MICROS, CUT_UNTIL_MICROS);
    assertThat(result.everyObserverHolds(sideB, sideA, MembershipList.Status.DEAD, CUT_FROM_MICROS))
        .isBetween(CUT_FROM_MICROS, CUT_UNTIL_MICROS);
    assertThat(result.countChanges(MembershipList.Status.DEAD, 0, CUT_FROM_MICROS)).isZero();
  }

  @ParameterizedTest
  @ValueSource(ints = {5, 7})
  void everyListAgreesAgainWithinTheBoundAfterTheHeal(int sideSize) {
    for (long seed = SEED; seed < SEED + SEEDS_PER_SPLIT; seed++) {
      MembershipCluster.Result result =
          MembershipCluster.run(settings.withSeed(seed), partition(sideSize));

      assertThat(result.convergedAtMicros()).isPositive();
      assertThat(result.convergedAtMicros() - CUT_UNTIL_MICROS)
          .isLessThanOrEqualTo(CONVERGENCE_BOUND_MICROS);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {5, 7})
  void noListRemovesALiveNodeAfterTheHeal(int sideSize) {
    for (long seed = SEED; seed < SEED + SEEDS_PER_SPLIT; seed++) {
      MembershipCluster.Result result =
          MembershipCluster.run(settings.withSeed(seed), partition(sideSize));

      assertThat(result.countChanges(MembershipList.Status.DEAD, CUT_UNTIL_MICROS, RUN_MICROS))
          .isZero();
    }
  }

  @Test
  void aNodeRemovedWhileAliveRejoinsOnlyWithAHigherIncarnation() {
    MembershipCluster.Result result = MembershipCluster.run(settings, partition(5));

    for (MembershipCluster.Change removal : result.changes()) {
      if (removal.status() != MembershipList.Status.DEAD) {
        continue;
      }
      assertThat(result.changes())
          .filteredOn(change -> change.observer().equals(removal.observer()))
          .filteredOn(change -> change.subject().equals(removal.subject()))
          .filteredOn(change -> change.atMicros() > removal.atMicros())
          .filteredOn(change -> change.status() == MembershipList.Status.ALIVE)
          .isNotEmpty()
          .allSatisfy(
              rejoin -> assertThat(rejoin.incarnation()).isGreaterThan(removal.incarnation()));
    }
    assertThat(result.staleAliveRefusedForDead()).isPositive();
  }

  @Test
  void theSameSeedGivesTheSameRunAndAnotherSeedAnother() {
    MembershipCluster.Result first = MembershipCluster.run(settings, partition(5));
    MembershipCluster.Result second = MembershipCluster.run(settings, partition(5));
    MembershipCluster.Result other =
        MembershipCluster.run(settings.withSeed(SEED + 1), partition(5));

    assertThat(second).isEqualTo(first);
    assertThat(other.changes()).isNotEqualTo(first.changes());
  }

  @Test
  void theSharedViewHidesThePartition() {
    MembershipCluster.Result result =
        MembershipCluster.run(
            settings.withViewModel(MembershipCluster.ViewModel.SHARED), partition(5));

    assertThat(
            result.countChanges(MembershipList.Status.SUSPECT, CUT_FROM_MICROS, CUT_UNTIL_MICROS))
        .isPositive();
    assertThat(result.countChanges(MembershipList.Status.DEAD, 0, RUN_MICROS)).isZero();
  }

  @Test
  void fullTablesGossipEveryRoundAndDeltasOnlyWhenThereIsNews() {
    MembershipCluster.Scenario quiet =
        new MembershipCluster.Scenario(CRASH_MICROS, List.of(), List.of());

    MembershipCluster.Result deltas = MembershipCluster.run(settings, quiet);
    MembershipCluster.Result tables =
        MembershipCluster.run(
            settings.withDissemination(MembershipCluster.Dissemination.FULL_TABLES), quiet);

    assertThat(tables.messages().get(MembershipCluster.MessageKind.GOSSIP)).isPositive();
    assertThat(tables.bytesSent()).isGreaterThan(deltas.bytesSent());
    assertThat(deltas.messages().getOrDefault(MembershipCluster.MessageKind.GOSSIP, 0L))
        .isLessThan(tables.messages().get(MembershipCluster.MessageKind.GOSSIP));
  }

  @Test
  void rejectsSettingsAndScenariosThatCannotRun() {
    assertThatThrownBy(() -> MembershipCluster.Settings.memberlistLan(1, SEED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nodeCount");
    assertThatThrownBy(() -> new MembershipCluster.Scenario(0, List.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("runMicros");
    assertThatThrownBy(() -> new MembershipCluster.Partition(Set.of("n1"), 2, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("from < until");
    assertThatThrownBy(() -> new MembershipCluster.Crash("n1", -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("atMicros");
  }

  private static MembershipCluster.Scenario partition(int sideSize) {
    return new MembershipCluster.Scenario(
        RUN_MICROS,
        List.of(),
        List.of(
            new MembershipCluster.Partition(side(1, sideSize), CUT_FROM_MICROS, CUT_UNTIL_MICROS)));
  }

  private static Set<String> side(int firstNumber, int lastNumber) {
    Set<String> nodes = new LinkedHashSet<>();
    for (int nodeNumber = firstNumber; nodeNumber <= lastNumber; nodeNumber++) {
      nodes.add("n" + nodeNumber);
    }
    return nodes;
  }
}
