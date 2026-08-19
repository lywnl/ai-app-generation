package com.lyw.appgeneration.ai.memory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** 同一用户回合共享的64K检查点模式状态。 */
public final class ContextCompressionAttemptState {

    private final AtomicReference<Slot> slot =
            new AtomicReference<>(new Slot(State.NORMAL, 0L));
    private final AtomicLong ownerSequence = new AtomicLong();

    public CheckpointClaim tryEnterCheckpointMode() {
        while (true) {
            Slot current = slot.get();
            if (current.state() == State.ACTIVE) {
                CheckpointClaim claim = claim(EnterDecision.REBUILD);
                if (slot.compareAndSet(current,
                        new Slot(State.REBUILDING, claim.ownerId()))) {
                    return claim;
                }
                continue;
            }
            if (current.state() == State.ENTERING
                    || current.state() == State.REBUILDING) {
                return new CheckpointClaim(EnterDecision.IN_PROGRESS, 0L);
            }
            if (current.state() == State.FAILED) {
                return new CheckpointClaim(EnterDecision.ALREADY_FAILED, 0L);
            }
            CheckpointClaim claim = claim(EnterDecision.FIRST_ENTRY);
            if (slot.compareAndSet(current,
                    new Slot(State.ENTERING, claim.ownerId()))) {
                return claim;
            }
        }
    }

    public boolean markCheckpointReady(CheckpointClaim claim) {
        return completeOwnedClaim(claim, State.ACTIVE);
    }

    public boolean markCheckpointFailed(CheckpointClaim claim) {
        return completeOwnedClaim(claim, State.FAILED);
    }

    public boolean checkpointProjectionRequired() {
        return slot.get().state() != State.NORMAL;
    }

    private CheckpointClaim claim(EnterDecision decision) {
        return new CheckpointClaim(decision, ownerSequence.incrementAndGet());
    }

    private boolean completeOwnedClaim(
            CheckpointClaim claim, State target) {
        if (claim == null || !claim.ownsAttempt()) {
            return false;
        }
        while (true) {
            Slot current = slot.get();
            if (current.ownerId() != claim.ownerId()
                    || !claim.owns(current.state())) {
                return false;
            }
            if (slot.compareAndSet(current, new Slot(target, 0L))) {
                return true;
            }
        }
    }

    public enum EnterDecision {
        FIRST_ENTRY,
        REBUILD,
        IN_PROGRESS,
        ALREADY_FAILED
    }

    public record CheckpointClaim(
            EnterDecision decision,
            long ownerId) {

        public CheckpointClaim {
            if (decision == null || ownerId < 0L) {
                throw new IllegalArgumentException("检查点认领状态不合法");
            }
        }

        private boolean ownsAttempt() {
            return decision == EnterDecision.FIRST_ENTRY
                    || decision == EnterDecision.REBUILD;
        }

        private boolean owns(State state) {
            return decision == EnterDecision.FIRST_ENTRY
                    && state == State.ENTERING
                    || decision == EnterDecision.REBUILD
                    && state == State.REBUILDING;
        }
    }

    private record Slot(State state, long ownerId) {
    }

    private enum State {
        NORMAL,
        ENTERING,
        ACTIVE,
        REBUILDING,
        FAILED
    }
}
