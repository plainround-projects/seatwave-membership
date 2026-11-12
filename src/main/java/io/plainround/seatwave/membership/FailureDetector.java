package io.plainround.seatwave.membership;

/**
 * What one SeatWave node believes about one other node, from the heartbeats it has received. A
 * detector never knows that a node is dead; it only reports how suspicious the silence since the
 * last heartbeat is, and from what silence it will call the node suspected. Each monitored node
 * gets its own instance; an instance is confined to one thread and is not thread-safe.
 */
sealed interface FailureDetector permits FixedTimeoutDetector, PhiAccrualDetector {

  /** Records a heartbeat that arrived at {@code arrivalMicros}, which must not go backwards. */
  void recordHeartbeat(long arrivalMicros);

  /** The arrival time of the last heartbeat recorded. */
  long lastHeartbeatMicros();

  /** The silence, counted from the last heartbeat, after which this detector suspects the node. */
  long suspectAfterMicros();

  /** The suspicion level at {@code nowMicros}; at or above {@link #threshold()} means suspected. */
  double suspicionLevel(long nowMicros);

  /** The level at which this detector suspects. */
  double threshold();

  /** A fresh detector with the same settings and no history, for another monitored node. */
  FailureDetector fresh();

  /** A short label for tables, such as "fixed 3.0 s" or "phi 8, pause 1 s". */
  String label();

  default boolean isSuspected(long nowMicros) {
    return nowMicros - lastHeartbeatMicros() > suspectAfterMicros();
  }
}
