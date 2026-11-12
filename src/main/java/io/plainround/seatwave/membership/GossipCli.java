package io.plainround.seatwave.membership;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * How many gossip rounds one fact needs to reach every node, against the log-of-N expectation, with
 * and without message loss, for push and push-pull, at 10 and 1,000 nodes (and 16 and 10,000 for
 * comparison). The expectation printed is Pittel's for push with fan-out 1, log2(N) + ln(N).
 * Otherwise it is a floor: the rounds if no message were ever wasted on a node that already knows,
 * log base (1 + fanout) of N for push, and log base (1 + 2 x fanout) of N for push-pull, where a
 * node also hears from the nodes that call it. The only class here that prints.
 */
final class GossipCli {
  private static final long SEED = 2_404;
  private static final int FIGURE_NODES = 16;
  private static final int FIGURE_FANOUT = 2;

  private record Experiment(
      int nodes, int fanout, double loss, GossipSimulation.Mode mode, int trials) {}

  private static final List<Experiment> EXPERIMENTS =
      List.of(
          new Experiment(10, 1, 0.0, GossipSimulation.Mode.PUSH, 10_000),
          new Experiment(10, 3, 0.0, GossipSimulation.Mode.PUSH, 10_000),
          new Experiment(10, 3, 0.3, GossipSimulation.Mode.PUSH, 10_000),
          new Experiment(16, 2, 0.0, GossipSimulation.Mode.PUSH, 10_000),
          new Experiment(1_000, 1, 0.0, GossipSimulation.Mode.PUSH, 1_000),
          new Experiment(1_000, 3, 0.0, GossipSimulation.Mode.PUSH, 1_000),
          new Experiment(1_000, 3, 0.1, GossipSimulation.Mode.PUSH, 1_000),
          new Experiment(1_000, 3, 0.3, GossipSimulation.Mode.PUSH, 1_000),
          new Experiment(1_000, 3, 0.3, GossipSimulation.Mode.PUSH_PULL, 1_000),
          new Experiment(1_000, 1, 0.0, GossipSimulation.Mode.PUSH_PULL, 1_000),
          new Experiment(10_000, 3, 0.0, GossipSimulation.Mode.PUSH, 200));

  private GossipCli() {}

  public static void main(String[] arguments) {
    GossipSimulation.Settings figure =
        new GossipSimulation.Settings(FIGURE_NODES, FIGURE_FANOUT, 0.0, GossipSimulation.Mode.PUSH);
    System.out.println(
        String.format(
            Locale.ROOT,
            "one spread, %d nodes, fan-out %d, push:" + " informed after each round = %s",
            FIGURE_NODES,
            FIGURE_FANOUT,
            Arrays.stream(GossipSimulation.informedPerRound(figure, SEED))
                .mapToObj(String::valueOf)
                .collect(Collectors.joining(", "))));
    System.out.println();
    System.out.println(
        String.format(
            Locale.ROOT,
            "%7s %6s %5s %-9s %7s %11s %4s %4s %13s %15s",
            "nodes",
            "fanout",
            "loss",
            "mode",
            "trials",
            "rounds mean",
            "p99",
            "max",
            "messages",
            "expectation"));
    for (Experiment experiment : EXPERIMENTS) {
      GossipSimulation.Summary summary =
          GossipSimulation.spread(
              new GossipSimulation.Settings(
                  experiment.nodes(), experiment.fanout(), experiment.loss(), experiment.mode()),
              experiment.trials(),
              SEED);
      System.out.println(
          String.format(
              Locale.ROOT,
              "%7d %6d %4.0f%% %-9s %7d %11.2f %4d %4d %13.0f %15s",
              experiment.nodes(),
              experiment.fanout(),
              experiment.loss() * 100,
              experiment.mode(),
              experiment.trials(),
              summary.meanRounds(),
              summary.p99Rounds(),
              summary.maxRounds(),
              summary.meanMessages(),
              expectation(experiment)));
    }
  }

  private static String expectation(Experiment experiment) {
    double nodes = experiment.nodes();
    if (experiment.fanout() == 1 && experiment.mode() == GossipSimulation.Mode.PUSH) {
      return String.format(
          Locale.ROOT, "%.2f (Pittel)", Math.log(nodes) / Math.log(2) + Math.log(nodes));
    }
    // With push-pull a node learns from the peers it calls and, on average, as many that call it.
    int contactsPerRound =
        experiment.mode() == GossipSimulation.Mode.PUSH_PULL
            ? 2 * experiment.fanout()
            : experiment.fanout();
    return String.format(
        Locale.ROOT, "%.2f (floor)", Math.log(nodes) / Math.log(1.0 + contactsPerRound));
  }
}
