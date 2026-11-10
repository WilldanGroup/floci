package io.floci.aws.services.cloudformation;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * The signals sent to each wait condition handle's URL, and the wait an
 * {@code AWS::CloudFormation::WaitCondition} holds its stack's create on until they arrive.
 *
 * <p>A handle collects signals from the moment its URL exists, so a signal sent before its wait
 * condition starts waiting, as a resource the condition {@code DependsOn} may send one, counts. As the
 * CloudFormation User Guide's page on wait conditions has it, a success signal is counted once per
 * {@code UniqueId}, a second signal with the same {@code UniqueId} being a retransmission that is
 * ignored, and one failure signal fails the condition. Held in memory: a wait outlives no restart of
 * the emulator, so neither do its signals.
 */
@ApplicationScoped
public class WaitConditionSignalStore {

    /** One signal's members, as the wait condition signal syntax gives them. */
    public record Signal(String status, String uniqueId, String data, String reason) {
    }

    /** A failure signal ended the wait. */
    public static final class WaitConditionFailedException extends Exception {
        private final Signal signal;

        WaitConditionFailedException(Signal signal) {
            super("WaitCondition received failed message: '" + signal.reason() + "' for uniqueId: "
                    + signal.uniqueId());
            this.signal = signal;
        }

        public Signal signal() {
            return signal;
        }
    }

    private static final class Signals {
        private final Map<String, Signal> successes = new LinkedHashMap<>();
        private Signal failure;
    }

    private final ConcurrentHashMap<String, Signals> byHandle = new ConcurrentHashMap<>();

    /** Records a signal sent to {@code handle} and wakes a wait on it. */
    public void receive(String handle, Signal signal) {
        Signals signals = byHandle.computeIfAbsent(handle, ignored -> new Signals());
        synchronized (signals) {
            if ("FAILURE".equals(signal.status())) {
                if (signals.failure == null) {
                    signals.failure = signal;
                }
            } else {
                signals.successes.putIfAbsent(signal.uniqueId(), signal);
            }
            signals.notifyAll();
        }
    }

    /**
     * Waits until {@code handle} has {@code count} success signals, and answers each one's
     * {@code UniqueId} and {@code Data} in the order they arrived.
     *
     * @throws WaitConditionFailedException when a failure signal arrives first
     * @throws TimeoutException when {@code timeout} passes first, saying how many arrived
     */
    public Map<String, String> await(String handle, int count, Duration timeout)
            throws WaitConditionFailedException, TimeoutException, InterruptedException {
        Signals signals = byHandle.computeIfAbsent(handle, ignored -> new Signals());
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (signals) {
            while (true) {
                if (signals.failure != null) {
                    throw new WaitConditionFailedException(signals.failure);
                }
                if (signals.successes.size() >= count) {
                    Map<String, String> data = new LinkedHashMap<>();
                    signals.successes.values().forEach(signal -> data.put(signal.uniqueId(), signal.data()));
                    return data;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new TimeoutException("WaitCondition timed out. Received " + signals.successes.size()
                            + " conditions when expecting " + count);
                }
                signals.wait(Math.max(1, remaining / 1_000_000L));
            }
        }
    }

    /** Forgets the handle's signals, once nothing will wait on them again. */
    public void discard(String handle) {
        byHandle.remove(handle);
    }
}
