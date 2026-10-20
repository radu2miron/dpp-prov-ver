package edu.tucn.dppprovver.contract;

import edu.tucn.dppprovver.model.AcceptanceDecision;
import edu.tucn.dppprovver.model.Conflict;
import edu.tucn.dppprovver.model.ConflictStatus;
import edu.tucn.dppprovver.model.DocType;
import edu.tucn.dppprovver.model.EventStatus;
import edu.tucn.dppprovver.model.LifecycleEvent;
import edu.tucn.dppprovver.model.Resolution;
import edu.tucn.dppprovver.model.ResolutionAction;
import edu.tucn.dppprovver.util.JsonMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hyperledger.fabric.contract.Context;

/** Adjudicates recorded conflicts without rewriting lifecycle history or applying state changes. */
public final class ConflictResolutionService {
    private final ActorService actorService;
    private final LifecycleEventService eventService;
    private final ConflictService conflictService;
    private final ResolutionService resolutionService;
    private final AcceptanceDecisionService decisionService;

    public ConflictResolutionService(ActorService actorService, LifecycleEventService eventService,
            ConflictService conflictService, ResolutionService resolutionService,
            AcceptanceDecisionService decisionService) {
        this.actorService = actorService;
        this.eventService = eventService;
        this.conflictService = conflictService;
        this.resolutionService = resolutionService;
        this.decisionService = decisionService;
    }

    /**
     * Records an immutable resolution and closes its conflict atomically.
     * ACCEPT_EVENT confirms a previously accepted event; REJECT_EVENT confirms a
     * previously rejected event. Neither promotes rejected events into product state.
     *
     * @param ctx the Fabric transaction context
     * @param json the JSON representation of a Resolution document
     * @return the recorded resolution as JSON
     */
    public String resolve(Context ctx, String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Resolution JSON must not be blank.");
        }
        Resolution resolution = JsonMapper.fromJson(json, Resolution.class);
        validate(resolution);
        actorService.read(ctx, resolution.getActorId());

        String resolutionKey = DocType.RESOLUTION.name() + ":" + resolution.getId();
        String existing = ctx.getStub().getStringState(resolutionKey);
        if (existing != null && !existing.isEmpty()) {
            Resolution previous = resolutionService.read(ctx, resolution.getId());
            if (!JsonMapper.toJson(previous).equals(JsonMapper.toJson(resolution))) {
                throw new IllegalArgumentException("Resolution id already belongs to another request.");
            }
            Conflict priorConflict = conflictService.read(ctx, resolution.getConflictId());
            if (priorConflict.getStatus() != ConflictStatus.RESOLVED
                    || !resolution.getId().equals(priorConflict.getResolutionId())) {
                throw new IllegalStateException("Resolution and conflict status disagree.");
            }
            return JsonMapper.toJson(previous);
        }

        Conflict conflict = conflictService.read(ctx, resolution.getConflictId());
        if (conflict.getStatus() != ConflictStatus.PENDING || conflict.getResolutionId() != null) {
            throw new IllegalArgumentException("Conflict is already resolved.");
        }
        if (conflict.getEventIds() == null || conflict.getEventIds().size() < 2
                || !conflict.getEventIds().contains(resolution.getSelectedEventId())) {
            throw new IllegalArgumentException("Selected event must belong to a multi-event conflict.");
        }

        Map<String, EventStatus> outcomes = new LinkedHashMap<>();
        for (String eventId : conflict.getEventIds()) {
            LifecycleEvent event = eventService.read(ctx, eventId);
            AcceptanceDecision decision = decisionService.read(ctx, "decision:" + eventId);
            if (!eventId.equals(event.getId()) || !eventId.equals(decision.getEventId())) {
                throw new IllegalStateException("Conflicting event and decision references disagree.");
            }
            outcomes.put(eventId, decision.getOutcome());
        }
        if (!outcomes.containsValue(EventStatus.ACCEPTED)
                || !outcomes.containsValue(EventStatus.REJECTED)) {
            throw new IllegalArgumentException("Conflict requires accepted and rejected event decisions.");
        }
        EventStatus selectedOutcome = outcomes.get(resolution.getSelectedEventId());
        if (resolution.getAction() == ResolutionAction.ACCEPT_EVENT
                && selectedOutcome != EventStatus.ACCEPTED) {
            throw new IllegalArgumentException("Only an already accepted event can be confirmed.");
        }
        if (resolution.getAction() == ResolutionAction.REJECT_EVENT
                && selectedOutcome != EventStatus.REJECTED) {
            throw new IllegalArgumentException("Only an already rejected event can be confirmed.");
        }

        resolutionService.create(ctx, JsonMapper.toJson(resolution));
        conflictService.markResolved(ctx, conflict.getId(), resolution.getId());
        return JsonMapper.toJson(resolution);
    }

    private static void validate(Resolution resolution) {
        if (resolution == null || resolution.getDocType() != DocType.RESOLUTION
                || invalid(resolution.getId()) || invalid(resolution.getConflictId())
                || invalid(resolution.getSelectedEventId()) || invalid(resolution.getActorId())
                || invalid(resolution.getPolicyRef()) || invalid(resolution.getReason())
                || resolution.getDecidedAt() == null) {
            throw new IllegalArgumentException("Incomplete Resolution document.");
        }
        if (resolution.getAction() != ResolutionAction.ACCEPT_EVENT
                && resolution.getAction() != ResolutionAction.REJECT_EVENT) {
            throw new IllegalArgumentException(
                    "Only ACCEPT_EVENT and REJECT_EVENT are supported at this stage.");
        }
    }

    private static boolean invalid(String s) {
        return s == null || s.isBlank() || s.indexOf('\0') >= 0;
    }
}
