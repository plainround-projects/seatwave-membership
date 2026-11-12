# Changelog

## 1.0.0 (12 November 2026)

- Built from the Module 24 code seed: `NetworkConditions`, `PauseSchedule`, the sealed `FailureDetector` with `FixedTimeoutDetector` and `PhiAccrualDetector`, `HeartbeatSimulation`, `MembershipList`, `GossipSimulation` and the four programs `DetectorSweepCli`, `SuspicionTimelineCli`, `GossipCli`, `SwimProbeCli`. Through Gradle the four programs print byte for byte what the seed printed on 11 November 2026.
- Gradle 9.9.1 wrapper pinned by checksum, Kotlin DSL, version catalog, dependency and settings lockfiles, `-Xlint:all -Werror`, Spotless with google-java-format 2.0.0, JaCoCo with a line floor of 0.80, one `JavaExec` task per program.
- Added `MembershipCluster`, a per-node simulation in which every node keeps its own `MembershipList` and learns only by messages (probes, acks, indirect probe requests, gossip of news, full-state exchanges, Serf-style reconnects), with `PartitionCli` (failure mode 5: a cut, 5 against 5 and 7 against 3, and the heal, beside the shared view) and `ClusterScaleCli` (removal time at 10 and 1,000 nodes, traffic per node at 1,000).
- Added to `MembershipList`: `learnDead` (news of a death confirmed by another list, refused when older than the node's refutation) and `members` (a snapshot for state exchanges). The existing methods are unchanged.
- `GossipSimulation.distinctPeers` is package-private so its two rules can be tested directly.
- Tests for `PhiAccrualDetector` (crossing point against a brute-force phi, window eviction), `GossipSimulation` (no message to self, distinct peers), `HeartbeatSimulation`, `MembershipCluster` and more of `MembershipList`, each checked by mutation (README, "Mutation checks").
