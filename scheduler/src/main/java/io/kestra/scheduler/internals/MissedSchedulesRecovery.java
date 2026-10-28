package io.kestra.scheduler.internals;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Optional;

import org.slf4j.event.Level;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.RecoverMissedSchedules;
import io.kestra.core.models.triggers.Schedulable;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.scheduler.model.TriggerState;
import io.kestra.core.utils.Logs;

import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;

/**
 * Decides which schedules a trigger catches up on when it resumes after a pause, such as a re-enable.
 */
@Singleton
public class MissedSchedulesRecovery {

    private final RunContextFactory runContextFactory;

    public MissedSchedulesRecovery(RunContextFactory runContextFactory) {
        this.runContextFactory = runContextFactory;
    }

    /**
     * Updates the state of a resumed trigger. When recovery is requested, missed schedules are recovered
     * according to the trigger's own {@code recoverMissedSchedules} configuration; the configuration is always
     * re-resolved (never overridden per action) so that this decision and the scheduler startup recovery
     * ({@code TriggerScheduler#onStart}) stay consistent across restarts and vNode re-assignments.
     * By default (no recovery requested), missed schedules are skipped by re-computing the next evaluation date.
     */
    public TriggerState updateForResumedTrigger(Clock clock, TriggerState state, Flow flow,
        AbstractTrigger trigger, @Nullable Boolean recoverMissedSchedules) {
        return switch (resolveRecoverMissedSchedules(flow, trigger, recoverMissedSchedules)) {
            // Keep the frozen past nextEvaluationDate so the scheduling loop replays each missed tick.
            case ALL -> state.getNextEvaluationDate() != null
                ? state
                : state.updateForNextEvaluationDate(clock, nextEvaluationDate(clock, flow, trigger, state.context()));
            case LAST -> updateForLastMissedSchedule(clock, state, flow, trigger);
            // Also advance evaluatedAt so the startup recovery cannot resurrect the skipped schedules.
            case NONE -> state
                .evaluatedAt(clock, ZonedDateTime.now(clock))
                .updateForNextEvaluationDate(clock, nextEvaluationDateSkippingMissed(clock, state, flow, trigger));
        };
    }

    private RecoverMissedSchedules resolveRecoverMissedSchedules(Flow flow, AbstractTrigger trigger,
        @Nullable Boolean recoverMissedSchedules) {
        if (!Boolean.TRUE.equals(recoverMissedSchedules) || !(trigger instanceof Schedulable schedulable)) {
            return RecoverMissedSchedules.NONE;
        }
        return Optional.ofNullable(schedulable.getRecoverMissedSchedules())
            .orElseGet(() -> schedulable.defaultRecoverMissedSchedules(runContextFactory.of(flow, trigger)));
    }

    private TriggerState updateForLastMissedSchedule(Clock clock, TriggerState state, Flow flow, AbstractTrigger trigger) {
        // A running backfill must resume untouched: recomputing the next evaluation date would re-derive
        // the backfill from the new date and silently drop or corrupt its remaining range.
        if (state.getBackfill() != null) {
            return state;
        }
        // Never evaluated (e.g. created disabled): there is no missed schedule to recover, and the last
        // cron tick before now could even predate the trigger's creation.
        if (state.getEvaluatedAt() == null) {
            return state.updateForNextEvaluationDate(clock, nextEvaluationDateSkippingMissed(clock, state, flow, trigger));
        }
        try {
            Optional<ZonedDateTime> previousDate = ((Schedulable) trigger).previousEvaluationDate(conditionContext(flow, trigger));
            if (previousDate.isPresent() && previousDate.get().toInstant().isAfter(state.getEvaluatedAt())) {
                return state.updateForNextEvaluationDate(clock, previousDate.get());
            }
            // Nothing was missed: keep the stored next evaluation date.
            return state;
        } catch (IllegalVariableEvaluationException e) {
            Logs.logTrigger(state, Level.WARN, "Cannot compute the previous evaluation date, missed schedules will be skipped. Cause: {}", e.getMessage());
            return state.updateForNextEvaluationDate(clock, nextEvaluationDateSkippingMissed(clock, state, flow, trigger));
        }
    }

    /**
     * Computes the next evaluation date of a resumed trigger so that missed schedules are skipped: the stale
     * evaluation date is dropped from the trigger context so the computation seeds from now, while a
     * running backfill is preserved so it resumes from its current date.
     */
    private ZonedDateTime nextEvaluationDateSkippingMissed(Clock clock, TriggerState state, Flow flow, AbstractTrigger trigger) {
        TriggerContext context = state.context().toBuilder().date(null).build();
        return nextEvaluationDate(clock, flow, trigger, context);
    }

    private ZonedDateTime nextEvaluationDate(Clock clock, Flow flow, AbstractTrigger trigger, TriggerContext triggerContext) {
        return NextEvaluationDate.get(clock, trigger, triggerContext, conditionContext(flow, trigger));
    }

    private ConditionContext conditionContext(Flow flow, AbstractTrigger trigger) {
        return ConditionContext.builder().flow(flow).runContext(runContextFactory.of(flow, trigger)).build();
    }
}
