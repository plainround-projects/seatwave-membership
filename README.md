# seatwave-membership

A heartbeat, failure-detection and gossip simulator for SeatWave's cluster of nodes `n1` to `n10` (and up to 1,000), run on a virtual clock. This is the project of Module 24, "Are you alive? Heartbeats and gossip", of the course System Design Fundamentals. It measures the two errors of a failure detector (detection time against false positives) for fixed timeouts and phi accrual thresholds, shows one garbage-collection pause through four detectors, counts gossip rounds and messages from 10 to 10,000 nodes, plays SWIM-style probing with indirect probes and suspicion, and, with every node keeping its own membership list, reproduces a network partition in which each side removes the other, and the heal.

Every number the programs print is a number of the model: delays, losses, pauses and message sizes are assumptions (the on-sale network has a one-way p99 of 200 ms and 0.1 percent loss; pauses reach 2 s), drawn from seeded sources on a virtual clock, so a run is the same on every machine. They are not measurements of memberlist, Pekko, Cassandra or any real network. No Docker, no database and no container is involved.

## Prerequisites

- JDK 25 (the Gradle toolchain finds it; built and tested with Eclipse Temurin 25.0.2, where 25.0.5+8 is the current release on 12 November 2026).
- Nothing else: `./gradlew` downloads Gradle 9.9.1 (checksum pinned) and the test libraries.

## Dependencies (each checked on Maven Central and the Gradle site on 12 November 2026; OSV listed no vulnerability)

| What | Coordinates | Version | License | Why the JDK alone is not enough |
|---|---|---|---|---|
| Gradle wrapper | `gradle-9.9.1-bin.zip`, distribution and wrapper jar checksums compared | 9.9.1 | Apache-2.0 | one build tool version for every reader |
| JUnit | `org.junit:junit-bom` (test) | 6.1.3 | EPL-2.0 | the test runner |
| AssertJ | `org.assertj:assertj-core` (test) | 3.27.7 | Apache-2.0 | readable assertions |
| Spotless, google-java-format | `com.diffplug.spotless`, `com.google.googlejavaformat:google-java-format` | 8.11.0, 2.0.0 | Apache-2.0 | the formatter |
| JaCoCo | `org.jacoco` | 0.8.16 | EPL-2.0 | line coverage, floor 0.80 |

The simulation itself uses only the JDK (`SplittableRandom` for every draw, `PriorityQueue` for the virtual clock).

## Commands and their real output

Build, format check, tests and coverage: `./gradlew clean check` (Gradle 9.9.1, JDK 25.0.2, Apple-silicon laptop, 12 November 2026):

```
$ ./gradlew clean check
> Task :clean
> Task :compileJava
> Task :processResources NO-SOURCE
> Task :classes
> Task :compileTestJava
> Task :processTestResources NO-SOURCE
> Task :testClasses
> Task :test
> Task :jacocoTestCoverageVerification
> Task :spotlessJava
> Task :spotlessJavaCheck
> Task :spotlessCheck
> Task :check
> Task :jacocoTestReport
BUILD SUCCESSFUL in 2s
8 actionable tasks: 8 executed
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.9.1/userguide/configuration_cache_enabling.html
[41 test lines omitted, every one PASSED: 41]
```

41 tests (JUnit counts each parameter of a parameterized test), 0 failed. line coverage of the non-program classes: 96.3 percent (735 of 763 lines; the floor is 80 percent) The programs are run by hand and their output is checked in the module.

The programs, one task each:

| Task | Program | What it prints |
|---|---|---|
| `./gradlew -q sweepRun` | `DetectorSweepCli` | detection time against false positives for five fixed timeouts and three phi thresholds, ten steady nodes and two busy ones, 200 simulated hours |
| `./gradlew -q timelineRun` | `SuspicionTimelineCli` | one watcher's view of a 2 s pause through four detectors |
| `./gradlew -q gossipRun` | `GossipCli` | gossip rounds and messages at 10, 16, 1,000 and 10,000 nodes, push and push-pull, with loss |
| `./gradlew -q swimRun` | `SwimProbeCli` | one hour of SWIM-style probing on one shared view, three policies, a lossy link and a crash |
| `./gradlew -q partitionRun` | `PartitionCli` | failure mode 5 with a list per node: a 60 s cut, 5 against 5 and 7 against 3, and the heal, beside the shared view |
| `./gradlew -q scaleRun` | `ClusterScaleCli` | crash to removal at 10 and 1,000 nodes, and messages and bytes a second per node at 1,000 |

The first four come from the module's code seed. Run through Gradle on 12 November 2026, each printed exactly the bytes captured when the module was written on 11 November:

```
DetectorSweepCli: ./gradlew output byte-identical to the seed's captured output
SuspicionTimelineCli: ./gradlew output byte-identical to the seed's captured output
GossipCli: ./gradlew output byte-identical to the seed's captured output
SwimProbeCli: ./gradlew output byte-identical to the seed's captured output
```

```
DetectorSweepCli: second run identical
SuspicionTimelineCli: second run identical
GossipCli: second run identical
SwimProbeCli: second run identical
PartitionCli: second run identical
ClusterScaleCli: second run identical
```

`./gradlew -q partitionRun`:

```
10 nodes, each with its own list; memberlist LAN settings: probe every 1000 ms, ack timeout 500 ms, 3 helpers, suspicion timeout 4 s, gossip to 3 nodes every 200 ms, each item sent 8 times, push-pull every 30 s, one dead member retried every 30 s; on-sale network; seed 2404
cut from 60.000 s to 120.000 s; run 300 s; times below are counted from the cut (+) or from the heal (heal +)

PARTITION 5 against 5, per-node view (side A n1 n2 n3 n4 n5, side B n6 n7 n8 n9 n10)
  side A about side B: first SUSPECT +1.244 s, first DEAD +5.244 s, every A list holds every B node DEAD +9.481 s
  side B about side A: first SUSPECT +1.730 s, first DEAD +5.730 s, every B list holds every A node DEAD +10.869 s
  removals of live nodes (list entries turned DEAD): 0 before the cut, 50 during it, 0 after the heal; 10 of 10 nodes removed by at least one list
  suspicions applied 78, refutations 10 (every one by the accused node itself: yes), stale ALIVE refused for a DEAD entry 10, messages 7865
  after the heal: first refutation heal +6.254 s; every list agrees, all ALIVE, heal +10.200 s; incarnations at the end: n1=1 n2=1 n3=1 n4=1 n5=1 n6=1 n7=1 n8=1 n9=1 n10=1
  n1 as n6's list saw it: SUSPECT 0 at 63.274 s; DEAD 0 at 66.845 s; ALIVE 1 at 129.817 s

PARTITION 5 against 5, one shared view (side A n1 n2 n3 n4 n5, side B n6 n7 n8 n9 n10)
  side A about side B: first SUSPECT +1.402 s, first DEAD +never, every A list holds every B node DEAD +never
  side B about side A: first SUSPECT +1.730 s, first DEAD +never, every B list holds every A node DEAD +never
  removals of live nodes (list entries turned DEAD): 0 before the cut, 0 during it, 0 after the heal; 0 of 10 nodes removed by at least one list
  suspicions applied 218, refutations 218 (every one by the accused node itself: yes), stale ALIVE refused for a DEAD entry 0, messages 7747
  after the heal: first refutation heal +0.244 s; every list agrees, all ALIVE, heal +1.400 s; incarnations at the end: n1=21 n2=21 n3=21 n4=25 n5=19 n6=24 n7=22 n8=22 n9=24 n10=19
  n1 as n6's list saw it: SUSPECT 0 at 62.606 s; SUSPECT 3 at 71.606 s; SUSPECT 4 at 75.606 s; SUSPECT 8 at 86.606 s; SUSPECT 13 at 99.606 s; SUSPECT 18 at 112.606 s

PARTITION 7 against 3, per-node view (side A n1 n2 n3 n4 n5 n6 n7, side B n8 n9 n10)
  side A about side B: first SUSPECT +1.524 s, first DEAD +5.524 s, every A list holds every B node DEAD +8.712 s
  side B about side A: first SUSPECT +1.730 s, first DEAD +5.730 s, every B list holds every A node DEAD +13.830 s
  removals of live nodes (list entries turned DEAD): 0 before the cut, 42 during it, 0 after the heal; 10 of 10 nodes removed by at least one list
  suspicions applied 76, refutations 10 (every one by the accused node itself: yes), stale ALIVE refused for a DEAD entry 10, messages 7820
  after the heal: first refutation heal +6.241 s; every list agrees, all ALIVE, heal +9.700 s; incarnations at the end: n1=1 n2=1 n3=1 n4=1 n5=1 n6=1 n7=1 n8=1 n9=1 n10=1
  n1 as n8's list saw it: SUSPECT 0 at 62.823 s; DEAD 0 at 66.823 s; ALIVE 1 at 129.413 s

PARTITION 7 against 3, one shared view (side A n1 n2 n3 n4 n5 n6 n7, side B n8 n9 n10)
  side A about side B: first SUSPECT +1.356 s, first DEAD +never, every A list holds every B node DEAD +never
  side B about side A: first SUSPECT +1.730 s, first DEAD +never, every B list holds every A node DEAD +never
  removals of live nodes (list entries turned DEAD): 0 before the cut, 0 during it, 0 after the heal; 0 of 10 nodes removed by at least one list
  suspicions applied 197, refutations 197 (every one by the accused node itself: yes), stale ALIVE refused for a DEAD entry 0, messages 7558
  after the heal: first refutation heal +0.186 s; every list agrees, all ALIVE, heal +1.500 s; incarnations at the end: n1=15 n2=15 n3=16 n4=19 n5=20 n6=16 n7=17 n8=27 n9=26 n10=26
  n1 as n8's list saw it: SUSPECT 2 at 71.948 s; SUSPECT 3 at 73.948 s; SUSPECT 6 at 83.948 s; SUSPECT 9 at 95.948 s; SUSPECT 10 at 101.948 s; SUSPECT 14 at 115.948 s
```

`./gradlew -q scaleRun` (about two minutes):

```
every node with its own list, memberlist LAN settings, on-sale network; one probe per node per second; sizes assumed: message header 40 bytes, member entry 40 bytes

 nodes crashes   suspicion   news sent    crash to first SUSPECT       crash to first DEAD  crash to DEAD everywhere live removals  messages/s     KB/s
                   timeout    per item         mean (min to max)         mean (min to max)         mean (min to max)                  per node per node
    10      40         4 s           8  2.058 s (1.007 to 4.600)  6.058 s (5.007 to 8.600)  6.311 s (5.243 to 8.884)             0        2.40     0.14
  1000       8        12 s          16  1.793 s (1.055 to 3.033) 13.793 s (13.055 to 15.033) 14.553 s (13.725 to 15.816)             0        2.79     3.36

 nodes gossip            run     messages/s per node by kind    messages/s   KB/s per node
  1000 deltas           30 s ping 1.05, ping-req 0.05, ack 1.09, gossip 0.00, push-pull 0.07          2.25            2.75
  1000 full tables      10 s ping 1.04, ping-req 0.04, ack 1.08, gossip 15.00, push-pull 0.07         17.24          603.49
```

The other four programs print the tables the module shows in Section 8.

## Mutation checks

Every rule a test guards was checked by changing one line of the main code in a scratch copy and running `./gradlew test`. A mutant is killed when a test fails; all 14 were killed. Script: `module-prompts/Generated/_tools/capture-module-24.sh` of the course folder; output: `_tools/module-24-outputs/mutants.txt`, repeated here:

```
mutant stale (MembershipList.java): an ALIVE with the known incarnation is accepted (an equal refutation overrides a suspicion)
  failed: MembershipClusterTest > aNodeRemovedWhileAliveRejoinsOnlyWithAHigherIncarnation()
  failed: MembershipListTest > aliveWithTheSuspectedIncarnationDoesNotCancelTheSuspicion()
  failed: MembershipListTest > rejectsRejoinWithTheIncarnationTheNodeWasRemovedWith()
  failed: MembershipListTest > staleAliveMessagesInAnyOrderNeverBringBackARemovedNode()
mutant early (MembershipList.java): a suspicion is confirmed one microsecond before its timeout
  failed: MembershipListTest > refusesRemovalBeforeTheSuspicionTimeout()
mutant revive (MembershipList.java): any ALIVE, however old, revives a DEAD entry
  failed: MembershipClusterTest > aNodeRemovedWhileAliveRejoinsOnlyWithAHigherIncarnation()
  failed: MembershipListTest > olderAliveMessagesNeverReviveADeadEntry()
  failed: MembershipListTest > rejectsRejoinWithTheIncarnationTheNodeWasRemovedWith()
  failed: MembershipListTest > staleAliveMessagesInAnyOrderNeverBringBackARemovedNode()
mutant restart (MembershipList.java): repeating a suspicion restarts its timer
  failed: MembershipClusterTest > aCrashedNodeIsNeverRefutedAndEndsDeadInEverySurvivorsList()
  failed: MembershipClusterTest > aNodeRemovedWhileAliveRejoinsOnlyWithAHigherIncarnation()
  failed: MembershipClusterTest > eachSideRemovesEveryNodeOfTheOtherDuringTheCut(int) > [1] 5
  failed: MembershipClusterTest > eachSideRemovesEveryNodeOfTheOtherDuringTheCut(int) > [2] 7
  failed: MembershipListTest > repeatingASuspicionDoesNotRestartItsTimer()
mutant stale-death (MembershipList.java): a death report older than the node's refutation is accepted
  failed: MembershipListTest > refusesADeathReportOlderThanTheNodesRefutation()
mutant proxy (MembershipCluster.java): a node that hears a suspicion of another raises that node's incarnation for it
  failed: MembershipClusterTest > aCrashedNodeIsNeverRefutedAndEndsDeadInEverySurvivorsList()
  failed: MembershipClusterTest > eachSideRemovesEveryNodeOfTheOtherDuringTheCut(int) > [1] 5
  failed: MembershipClusterTest > eachSideRemovesEveryNodeOfTheOtherDuringTheCut(int) > [2] 7
  failed: MembershipClusterTest > everyListAgreesAgainWithinTheBoundAfterTheHeal(int) > [1] 5
  failed: MembershipClusterTest > everyListAgreesAgainWithinTheBoundAfterTheHeal(int) > [2] 7
  failed: MembershipClusterTest > everyRefutationIsMadeByTheAccusedNodeItself()
  failed: MembershipClusterTest > noListRemovesALiveNodeAfterTheHeal(int) > [1] 5
mutant ignore-death (MembershipCluster.java): a node that hears it was declared DEAD does not refute (no rejoin)
  failed: MembershipClusterTest > everyListAgreesAgainWithinTheBoundAfterTheHeal(int) > [2] 7
  failed: MembershipClusterTest > noListRemovesALiveNodeAfterTheHeal(int) > [2] 7
mutant no-reconnect (MembershipCluster.java): no node ever tries a member it holds DEAD (no Serf reconnect)
  failed: MembershipClusterTest > aNodeRemovedWhileAliveRejoinsOnlyWithAHigherIncarnation()
  failed: MembershipClusterTest > everyListAgreesAgainWithinTheBoundAfterTheHeal(int) > [1] 5
  failed: MembershipClusterTest > everyListAgreesAgainWithinTheBoundAfterTheHeal(int) > [2] 7
  failed: MembershipClusterTest > everyRefutationIsMadeByTheAccusedNodeItself()
mutant dead-merge (MembershipCluster.java): a DEAD entry in another node's full state removes the member at once
  failed: MembershipClusterTest > noListRemovesALiveNodeAfterTheHeal(int) > [1] 5
  failed: MembershipClusterTest > noListRemovesALiveNodeAfterTheHeal(int) > [2] 7
mutant unseeded (MembershipCluster.java): the network's draws ignore the seed
  failed: MembershipClusterTest > theSameSeedGivesTheSameRunAndAnotherSeedAnother()
mutant eviction (PhiAccrualDetector.java): an interval leaving the window stays in the mean
  failed: PhiAccrualDetectorTest > forgetsIntervalsThatLeftTheWindow()
  failed: PhiAccrualDetectorTest > reportsTheSamePhiAsTheBruteForceAtEverySilence()
  failed: PhiAccrualDetectorTest > suspectsAtTheSilenceWhereABruteForcePhiFirstReachesTheThreshold()
mutant search (PhiAccrualDetector.java): the bisection for the crossing point searches the wrong half
  failed: PhiAccrualDetectorTest > suspectsAtTheSilenceWhereABruteForcePhiFirstReachesTheThreshold()
mutant self (GossipSimulation.java): a node may choose itself as a gossip peer
  failed: GossipSimulationTest > neverChoosesTheCallerAsItsOwnPeer()
mutant repeat (GossipSimulation.java): a node may choose the same peer twice in one round
  failed: GossipSimulationTest > choosesFanoutDifferentPeers()
```

Repeated runs and other folders (12 November 2026, no extra load added because other measurements were running on the machine; the simulations never read the wall clock):

```
./gradlew test --rerun-tasks: 40 of 40 runs passed
path length of the long copy: 660 characters
long path: clean check passed
folder with a space: clean check passed
```

## Project structure

```
src/main/java/io/plainround/seatwave/membership/   detectors, heartbeat engine, membership list, gossip,
                                                    the per-node cluster, and the six *Cli programs
src/test/java/io/plainround/seatwave/membership/   unit tests
build.gradle.kts  settings.gradle.kts  gradle/libs.versions.toml  gradle.lockfile  settings-gradle.lockfile
ARCHITECTURE.md  CHANGELOG.md  LICENSE
```

## What is simplified

- One virtual clock and one thread: no real network, no real garbage collector, no clock drift.
- Delays, losses, pauses and message sizes are assumptions. Rerunning the sweep on a week of inter-arrival times and pauses measured on a real cluster, and a logging-only week of the chosen detector beside the old rule, need a real cluster and were not run (the module marks them `[not run]`).
- The per-node cluster follows memberlist and Serf (probe, indirect probes, suspicion with a fixed timeout, gossip of news a bounded number of times, push-pull every 30 s, reconnect to a dead member every 30 s) but not Lifeguard's shrinking suspicion timeout or local health awareness, not piggybacking on probes, not message size limits, encryption or authentication.
- `SwimProbeCli` keeps the seed's one shared view on purpose; `PartitionCli` prints both views so the difference can be seen.
- The heartbeat engine (`HeartbeatSimulation`) models all-to-all heartbeats, which the module rejects beyond a few dozen nodes; it is the clearest place to see a detector's two errors.

## No CI on purpose

There is no `.github/` folder and no workflow. `./gradlew clean check` is the gate and runs on a laptop in under a minute.
