package edu.tucn.dppprovver.contract;

import edu.tucn.dppprovver.model.AcceptanceDecision;
import edu.tucn.dppprovver.model.EventStatus;
import edu.tucn.dppprovver.model.LifecycleEvent;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.hyperledger.fabric.contract.Context;

/** Validates causal dependencies and assigns deterministic vector clocks. */
final class CausalVersionService {
    enum Relation { BEFORE, AFTER, CONCURRENT, EQUAL }

    private final LifecycleEventService eventService;
    private final AcceptanceDecisionService decisionService;

    CausalVersionService(LifecycleEventService eventService,
            AcceptanceDecisionService decisionService) {
        this.eventService = eventService;
        this.decisionService = decisionService;
    }

    /** Merges accepted parent clocks and the actor's previous clock, then advances its component. */
    void prepare(Context ctx, LifecycleEvent event) {
        TreeMap<String, Long> calculated = new TreeMap<>();
        Set<String> parents = event.getParentEventIds();
        if (parents != null) {
            if (parents.stream().anyMatch(id -> id == null || id.isBlank())) {
                throw new IllegalArgumentException("Invalid parent event identifier.");
            }
            for (String parentId : new TreeSet<>(parents)) {
                if (parentId == null || parentId.isBlank() || parentId.equals(event.getId())) {
                    throw new IllegalArgumentException("Invalid parent event identifier.");
                }
                LifecycleEvent parent = eventService.read(ctx, parentId);
                AcceptanceDecision decision = decisionService.read(ctx, "decision:" + parentId);
                if (decision.getOutcome() != EventStatus.ACCEPTED) {
                    throw new IllegalArgumentException("Parent event " + parentId + " is not ACCEPTED.");
                }
                merge(calculated, parent.getVectorClock());
            }
        }

        String previousId = ctx.getStub().getStringState(actorHeadKey(event.getActorId()));
        if (previousId != null && !previousId.isBlank()) {
            if (previousId.equals(event.getId())) {
                throw new IllegalArgumentException("Event identifier already used by this actor.");
            }
            merge(calculated, eventService.read(ctx, previousId).getVectorClock());
        }

        long count = calculated.getOrDefault(event.getActorId(), 0L);
        calculated.put(event.getActorId(), Math.addExact(count, 1L));
        Map<String, Long> supplied = event.getVectorClock();
        if (supplied != null && !supplied.isEmpty() && !supplied.equals(calculated)) {
            throw new IllegalArgumentException("Provided vectorClock differs from the verified causal clock: "
                    + calculated);
        }
        event.setVectorClock(calculated);
    }

    /** Advances the actor's causal head within the same Fabric transaction. */
    void record(Context ctx, LifecycleEvent event) {
        ctx.getStub().putStringState(actorHeadKey(event.getActorId()), event.getId());
    }

    /** Vector-clock partial order (not Fabric transaction order). */
    static Relation compare(Map<String, Long> first, Map<String, Long> second) {
        Set<String> actors = new HashSet<>();
        if (first != null) actors.addAll(first.keySet());
        if (second != null) actors.addAll(second.keySet());
        boolean less = false;
        boolean greater = false;
        for (String actor : actors) {
            long a = first == null ? 0L : first.getOrDefault(actor, 0L);
            long b = second == null ? 0L : second.getOrDefault(actor, 0L);
            if (a < b) less = true;
            if (a > b) greater = true;
            if (less && greater) return Relation.CONCURRENT;
        }
        if (less) return Relation.BEFORE;
        if (greater) return Relation.AFTER;
        return Relation.EQUAL;
    }

    private static void merge(Map<String, Long> target, Map<String, Long> source) {
        if (source != null) {
            for (Map.Entry<String, Long> entry : source.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()
                        || entry.getValue() == null || entry.getValue() < 0L) {
                    throw new IllegalStateException("A stored event has an invalid vector clock.");
                }
                target.merge(entry.getKey(), entry.getValue(), Math::max);
            }
        }
    }

    private static String actorHeadKey(String actorId) {
        return "CAUSAL_ACTOR_HEAD:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(actorId.getBytes(StandardCharsets.UTF_8));
    }
}
