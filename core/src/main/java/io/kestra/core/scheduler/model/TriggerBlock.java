package io.kestra.core.scheduler.model;

import java.util.List;
import java.util.Objects;

import io.kestra.core.exceptions.FlowBlockedException;

/**
 * Why governance currently rejects a trigger's flow.
 */
public record TriggerBlock(FlowBlockedException.Reason reason, List<String> policyIds, String message) {

    public static TriggerBlock from(FlowBlockedException e) {
        return new TriggerBlock(e.getReason(), e.getPolicyIds(), e.getMessage());
    }

    /**
     * The message is left out: a failed check embeds its cause, which can differ on every attempt.
     */
    public boolean isSameBlockAs(TriggerBlock other) {
        return other != null && reason == other.reason && Objects.equals(policyIds, other.policyIds);
    }
}
