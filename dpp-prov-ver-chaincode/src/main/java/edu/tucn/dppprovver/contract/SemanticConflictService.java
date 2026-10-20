package edu.tucn.dppprovver.contract;

import edu.tucn.dppprovver.model.Conflict;
import edu.tucn.dppprovver.model.ConflictStatus;
import edu.tucn.dppprovver.model.ConflictType;
import edu.tucn.dppprovver.model.ContainmentInterval;
import edu.tucn.dppprovver.model.InspectionResult;
import edu.tucn.dppprovver.model.LifecycleEvent;
import edu.tucn.dppprovver.model.LifecycleEventData;
import edu.tucn.dppprovver.model.LifecycleEventType;
import edu.tucn.dppprovver.util.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.hyperledger.fabric.contract.Context;

/** Detects incompatible concurrent lifecycle events using causal frontiers. */
final class SemanticConflictService {
    private final LifecycleEventService eventService;
    private final ContainmentIntervalService containmentService;
    private final ConflictService conflictService;

    SemanticConflictService(LifecycleEventService eventService,
            ContainmentIntervalService containmentService, ConflictService conflictService) {
        this.eventService = eventService;
        this.containmentService = containmentService;
        this.conflictService = conflictService;
    }

    /** Reads the frontier for each affected target and finds incompatible concurrent events. */
    Analysis analyze(Context ctx, LifecycleEvent event) {
        Map<String, Set<String>> frontiers = new LinkedHashMap<>();
        Set<String> conflicts = new TreeSet<>();
        Map<String, LifecycleEvent> knownEvents = new HashMap<>();
        for (String target : targets(ctx, event)) {
            String json = ctx.getStub().getStringState(frontierKey(target));
            Set<String> ids = new TreeSet<>();
            if (json != null && !json.isBlank()) {
                String[] stored = JsonMapper.fromJson(json, String[].class);
                if (stored == null) throw new IllegalStateException("Invalid causal frontier.");
                Collections.addAll(ids, stored);
            }
            frontiers.put(target, ids);
            for (String otherId : ids) {
                LifecycleEvent other = knownEvents.computeIfAbsent(otherId,
                        id -> eventService.read(ctx, id));
                CausalVersionService.Relation relation = CausalVersionService.compare(
                        event.getVectorClock(), other.getVectorClock());
                if ((relation == CausalVersionService.Relation.CONCURRENT
                        || relation == CausalVersionService.Relation.BEFORE)
                        && incompatible(event, other, target)) {
                    conflicts.add(otherId);
                }
            }
        }
        return new Analysis(frontiers, conflicts, knownEvents);
    }

    /** Commits a conflict record; the lifecycle projection remains unchanged. */
    void recordConflict(Context ctx, LifecycleEvent event, Analysis analysis) {
        if (analysis.conflictingIds().isEmpty()) return;
        Conflict conflict = new Conflict();
        conflict.setId("causal:" + event.getId());
        conflict.setStatus(ConflictStatus.PENDING);
        conflict.setType(event.getType() == LifecycleEventType.INSTALL
                ? ConflictType.STRUCTURAL : ConflictType.LIFECYCLE_TRANSITION);
        Set<String> eventIds = new LinkedHashSet<>(analysis.conflictingIds());
        eventIds.add(event.getId());
        conflict.setEventIds(eventIds);
        conflictService.create(ctx, JsonMapper.toJson(conflict));
    }

    /** Updates accepted causal frontiers with MVCC-protected direct ledger keys. */
    void recordAccepted(Context ctx, LifecycleEvent event, Analysis analysis) {
        for (Map.Entry<String, Set<String>> entry : analysis.frontiers().entrySet()) {
            Set<String> retained = new TreeSet<>();
            for (String id : entry.getValue()) {
                LifecycleEvent previous = analysis.previous().get(id);
                if (previous == null) previous = eventService.read(ctx, id);
                CausalVersionService.Relation relation = CausalVersionService.compare(
                        event.getVectorClock(), previous.getVectorClock());
                if (relation == CausalVersionService.Relation.CONCURRENT
                        || relation == CausalVersionService.Relation.BEFORE) {
                    retained.add(id);
                }
            }
            retained.add(event.getId());
            ctx.getStub().putStringState(frontierKey(entry.getKey()), JsonMapper.toJson(retained));
        }
    }

    private List<String> targets(Context ctx, LifecycleEvent event) {
        LifecycleEventData data = event.getData();
        Set<String> keys = new TreeSet<>();
        switch (event.getType()) {
            case INSTALL -> {
                addComponent(keys, data.getComponentId());
                addSlot(keys, data.getHostId(), data.getSlotId());
            }
            case REMOVE -> {
                addComponent(keys, data.getComponentId());
                if (nonblank(data.getComponentId())) {
                    String active = ctx.getStub().getStringState(componentGuard(data.getComponentId()));
                    if (nonblank(active)) {
                        ContainmentInterval interval = containmentService.read(ctx, active);
                        addSlot(keys, interval.getHostId(), interval.getSlotId());
                    }
                }
            }
            case INSPECT, REPAIR, REUSE -> addComponent(keys, data.getAssetId());
            default -> { }
        }
        return new ArrayList<>(keys);
    }

    private static boolean incompatible(LifecycleEvent current, LifecycleEvent previous,
            String target) {
        LifecycleEventType a = current.getType();
        LifecycleEventType b = previous.getType();
        if (target.startsWith("SLOT:")) {
            return a == LifecycleEventType.INSTALL && b == LifecycleEventType.INSTALL;
        }
        if (a == LifecycleEventType.INSPECT && b == LifecycleEventType.INSPECT) {
            InspectionResult ar = current.getData().getInspectionResult();
            InspectionResult br = previous.getData().getInspectionResult();
            return !Objects.equals(ar, br);
        }
        if (a == LifecycleEventType.INSPECT || b == LifecycleEventType.INSPECT) {
            return a == LifecycleEventType.REPAIR || b == LifecycleEventType.REPAIR
                    || a == LifecycleEventType.REUSE || b == LifecycleEventType.REUSE;
        }
        // Structural changes, repairs and reuse approvals on one component cannot
        // be merged without a causal order, even when Fabric serialized their commits.
        return true;
    }

    private static void addComponent(Collection<String> keys, String id) {
        if (nonblank(id)) keys.add("COMPONENT:" + encode(id));
    }

    private static void addSlot(Collection<String> keys, String host, String slot) {
        if (nonblank(host) && nonblank(slot)) {
            keys.add("SLOT:" + encode(host) + ":" + encode(slot));
        }
    }

    private static String componentGuard(String componentId) {
        return "ACTIVE_COMPONENT:" + encode(componentId);
    }

    private static String frontierKey(String target) {
        return "CAUSAL_FRONTIER:" + target;
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                value.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean nonblank(String value) {
        return value != null && !value.isBlank();
    }

    record Analysis(Map<String, Set<String>> frontiers, Set<String> conflictingIds,
            Map<String, LifecycleEvent> previous) { }
}
