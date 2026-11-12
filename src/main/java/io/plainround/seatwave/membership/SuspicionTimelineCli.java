package io.plainround.seatwave.membership;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * One watcher's view of one node through a 2 s garbage-collection pause: n1 watches n4, whose
 * heartbeats leave every second and cross the on-sale network. The program prints, every 250 ms
 * around the pause, the silence since the last heartbeat and what four detectors make of it, so the
 * learner sees suspicion rise during the pause and fall when the next heartbeat lands. The only
 * class here that prints.
 */
final class SuspicionTimelineCli {
  private static final long MICROS_PER_MILLI = 1_000;
  private static final long MICROS_PER_SECOND = 1_000_000;
  private static final long HEARTBEAT_INTERVAL_MICROS = MICROS_PER_SECOND;
  private static final long FIRST_SEND_MICROS = 250 * MICROS_PER_MILLI;
  private static final long RUN_MICROS = 130 * MICROS_PER_SECOND;
  // The walkthrough's worst pause: 2 s, starting just after n4's heartbeat of 120.250 s left.
  private static final long PAUSE_START_MICROS = 120_300 * MICROS_PER_MILLI;
  private static final long PAUSE_MICROS = 2 * MICROS_PER_SECOND;
  private static final long WINDOW_START_MICROS = 119 * MICROS_PER_SECOND;
  private static final long WINDOW_END_MICROS = 125 * MICROS_PER_SECOND;
  private static final long STEP_MICROS = 250 * MICROS_PER_MILLI;
  private static final long SEED = 2_404;
  private static final double PHI_THRESHOLD = 8.0;
  private static final int PHI_WINDOW = 1_000;
  private static final long PHI_MIN_STD_DEVIATION_MICROS = 100 * MICROS_PER_MILLI;
  private static final long PHI_ACCEPTABLE_PAUSE_MICROS = 1_000 * MICROS_PER_MILLI;
  private static final long FIXED_SHORT_MICROS = 2 * MICROS_PER_SECOND;
  private static final long FIXED_LONG_MICROS = 3 * MICROS_PER_SECOND;

  private SuspicionTimelineCli() {}

  public static void main(String[] arguments) {
    long[] arrivals =
        HeartbeatSimulation.freshArrivals(
            sendsAroundPause(), NetworkConditions.onSale(), new SplittableRandom(SEED));
    List<FailureDetector> detectors =
        List.of(
            phi(PHI_ACCEPTABLE_PAUSE_MICROS),
            phi(0),
            new FixedTimeoutDetector(FIXED_SHORT_MICROS),
            new FixedTimeoutDetector(FIXED_LONG_MICROS));
    System.out.println(
        "n1 watching n4: heartbeats every 1 s, on-sale network, n4 paused 2.000 s"
            + " from 120.300 s");
    System.out.println(
        "phi A: threshold 8, acceptable pause 1 s; phi B: threshold 8, no acceptable"
            + " pause; fixed 2 s; fixed 3 s");
    System.out.println(
        String.format(
            Locale.ROOT,
            "%9s %10s %8s %8s %10s %10s",
            "time",
            "silence",
            "phi A",
            "phi B",
            "fixed 2 s",
            "fixed 3 s"));
    int nextArrival = 0;
    for (long nowMicros = 0; nowMicros <= WINDOW_END_MICROS; nowMicros += STEP_MICROS) {
      // Every heartbeat that has landed by now is recorded first, as the watcher would.
      while (nextArrival < arrivals.length && arrivals[nextArrival] <= nowMicros) {
        for (FailureDetector detector : detectors) {
          printSuspicionEpisode(detector, arrivals[nextArrival]);
          detector.recordHeartbeat(arrivals[nextArrival]);
        }
        if (arrivals[nextArrival] >= WINDOW_START_MICROS) {
          System.out.println(
              String.format(
                  Locale.ROOT,
                  "%8.3f s   heartbeat from n4 lands",
                  arrivals[nextArrival] / (double) MICROS_PER_SECOND));
        }
        nextArrival++;
      }
      if (nowMicros >= WINDOW_START_MICROS) {
        printStep(nowMicros, detectors);
      }
    }
  }

  private static void printStep(long nowMicros, List<FailureDetector> detectors) {
    FailureDetector first = detectors.getFirst();
    System.out.println(
        String.format(
            Locale.ROOT,
            "%7.3f s %8.3f s %8.2f %8.2f %10s %10s",
            nowMicros / (double) MICROS_PER_SECOND,
            (nowMicros - first.lastHeartbeatMicros()) / (double) MICROS_PER_SECOND,
            level(detectors.get(0), nowMicros),
            level(detectors.get(1), nowMicros),
            verdict(detectors.get(2), nowMicros),
            verdict(detectors.get(3), nowMicros)));
  }

  private static void printSuspicionEpisode(FailureDetector detector, long arrivalMicros) {
    long suspectedFromMicros = detector.lastHeartbeatMicros() + detector.suspectAfterMicros();
    // A suspicion that began before this heartbeat landed ends with it: print the episode whole.
    if (detector.isSuspected(arrivalMicros) && suspectedFromMicros >= WINDOW_START_MICROS) {
      System.out.println(
          String.format(
              Locale.ROOT,
              "%8.3f s   %s suspected n4 from %.3f s until this heartbeat (%.3f s of suspicion)",
              arrivalMicros / (double) MICROS_PER_SECOND,
              detector.label(),
              suspectedFromMicros / (double) MICROS_PER_SECOND,
              (arrivalMicros - suspectedFromMicros) / (double) MICROS_PER_SECOND));
    }
  }

  private static String verdict(FailureDetector detector, long nowMicros) {
    return detector.isSuspected(nowMicros) ? "SUSPECT" : "alive";
  }

  private static double level(FailureDetector detector, long nowMicros) {
    // Phi below zero is rounding in the tail approximation; show it as zero.
    return Math.max(detector.suspicionLevel(nowMicros), 0.0) + 0.0;
  }

  private static FailureDetector phi(long acceptablePauseMicros) {
    return new PhiAccrualDetector(
        new PhiAccrualDetector.Settings(
            PHI_THRESHOLD,
            PHI_WINDOW,
            PHI_MIN_STD_DEVIATION_MICROS,
            acceptablePauseMicros,
            HEARTBEAT_INTERVAL_MICROS));
  }

  private static List<Long> sendsAroundPause() {
    List<Long> sends = new ArrayList<>();
    long pauseEndMicros = PAUSE_START_MICROS + PAUSE_MICROS;
    for (long dueMicros = FIRST_SEND_MICROS;
        dueMicros < RUN_MICROS;
        dueMicros += HEARTBEAT_INTERVAL_MICROS) {
      boolean isDuringPause = dueMicros >= PAUSE_START_MICROS && dueMicros < pauseEndMicros;
      // Heartbeats due during the pause leave together, once, when the pause ends.
      long sendMicros = isDuringPause ? pauseEndMicros : dueMicros;
      if (sends.isEmpty() || sends.getLast() != sendMicros) {
        sends.add(sendMicros);
      }
    }
    return sends;
  }
}
