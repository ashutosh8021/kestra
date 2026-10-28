package io.kestra.core.exceptions;

import java.io.Serial;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;

import io.kestra.core.utils.Enums;

import lombok.Getter;

/**
 * Raised when a flow is rejected by runtime governance and must not run.
 *
 * <p>
 * Never thrown by the open-source edition; editions enforcing governance rules throw it from
 * {@code FlowParsingService#parseForRuntime}. Callers react by type: the executor path surfaces it as a failed
 * execution, the scheduler path skips the flow's triggers.
 * </p>
 */
@Getter
public class FlowBlockedException extends FlowProcessingException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Reason reason;
    private final List<String> policyIds;

    public FlowBlockedException(String message) {
        this(message, (Throwable) null);
    }

    public FlowBlockedException(String message, Throwable cause) {
        super(message, cause);
        this.reason = Reason.CHECK_FAILED;
        this.policyIds = List.of();
    }

    public FlowBlockedException(String message, List<String> policyIds) {
        super(message);
        this.reason = Reason.POLICY;
        this.policyIds = List.copyOf(policyIds);
    }

    public enum Reason {
        UNKNOWN,
        // a policy rule rejected the flow
        POLICY,
        // the policies could not be checked, so the flow is rejected rather than run ungoverned
        CHECK_FAILED;

        @JsonCreator
        public static Reason fromString(final String value) {
            return Enums.getForNameIgnoreCase(value, Reason.class, UNKNOWN);
        }
    }
}
