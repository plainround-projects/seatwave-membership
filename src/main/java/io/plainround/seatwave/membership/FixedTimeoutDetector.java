package io.plainround.seatwave.membership;

import java.util.Locale;

/**
 * The simplest detector: suspect a node when no heartbeat has arrived for a fixed timeout. Its
 * suspicion level is the silence divided by the timeout, so 1.0 is the threshold. It ignores what
 * the network and the node did before; the same timeout applies to a quiet node and a busy one. Not
 * thread-safe; one instance per monitored node.
 */
final class FixedTimeoutDetector implements FailureDetector {
  private static final double MICROS_PER_SECOND = 1_000_000.0;
  private static final double THRESHOLD = 1.0;

  private final long timeoutMicros;
  private long lastHeartbeatMicros;

  FixedTimeoutDetector(long timeoutMicros) {
    if (timeoutMicros <= 0) {
      throw new IllegalArgumentException("timeoutMicros must be positive, was " + timeoutMicros);
    }
    this.timeoutMicros = timeoutMicros;
  }

  @Override
  public void recordHeartbeat(long arrivalMicros) {
    if (arrivalMicros < lastHeartbeatMicros) {
      throw new IllegalArgumentException("heartbeat arrivals must not go backwards");
    }
    lastHeartbeatMicros = arrivalMicros;
  }

  @Override
  public long lastHeartbeatMicros() {
    return lastHeartbeatMicros;
  }

  @Override
  public long suspectAfterMicros() {
    return timeoutMicros;
  }

  @Override
  public double suspicionLevel(long nowMicros) {
    return (nowMicros - lastHeartbeatMicros) / (double) timeoutMicros;
  }

  @Override
  public double threshold() {
    return THRESHOLD;
  }

  @Override
  public FailureDetector fresh() {
    return new FixedTimeoutDetector(timeoutMicros);
  }

  @Override
  public String label() {
    return String.format(Locale.ROOT, "fixed %.1f s", timeoutMicros / MICROS_PER_SECOND);
  }
}
