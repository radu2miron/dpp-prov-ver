package edu.tucn.dppprovver.contract;

import edu.tucn.dppprovver.model.AcceptanceDecision;
import edu.tucn.dppprovver.model.Asset;
import edu.tucn.dppprovver.model.Conflict;
import edu.tucn.dppprovver.model.ConflictStatus;
import edu.tucn.dppprovver.model.ConflictType;
import edu.tucn.dppprovver.model.ContainmentInterval;
import edu.tucn.dppprovver.model.DocType;
import edu.tucn.dppprovver.model.EventStatus;
import edu.tucn.dppprovver.model.Inspection;
import edu.tucn.dppprovver.model.InspectionResult;
import edu.tucn.dppprovver.model.LifecycleEventType;
import edu.tucn.dppprovver.model.LifecycleEvent;
import edu.tucn.dppprovver.model.LifecycleEventData;
import edu.tucn.dppprovver.util.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.hyperledger.fabric.contract.Context;

/** Validates and atomically submits supported DPP lifecycle operations. */
public class LifecycleEventService extends AbstractLedgerService<LifecycleEvent> {
    private static final String POLICY_REF = "LIFECYCLE_V1";

    private final ActorService actorService;
    private final DigitalProductPassportService dppService;
    private final AssetService assetService;
    private final ContainmentIntervalService containmentService;
    private final InspectionService inspectionService;
    private final AcceptanceDecisionService decisionService;
    private final ConflictService conflictService;
    private final CausalVersionService causalVersionService;
    private final SemanticConflictService semanticConflictService;
    private final TemporalProvenanceService temporalProvenanceService;
    private final DppAssetAssociationService dppAssociations;

    public LifecycleEventService(ActorService actorService,
            DigitalProductPassportService dppService, AssetService assetService,
            ContainmentIntervalService containmentService, InspectionService inspectionService,
            AcceptanceDecisionService decisionService, ConflictService conflictService) {
        super(LifecycleEvent.class, DocType.LIFECYCLE_EVENT,
                LifecycleEvent::getId, LifecycleEvent::getDocType);
        this.actorService = actorService;
        this.dppService = dppService;
        this.assetService = assetService;
        this.containmentService = containmentService;
        this.inspectionService = inspectionService;
        this.decisionService = decisionService;
        this.conflictService = conflictService;
        this.causalVersionService = new CausalVersionService(this, decisionService);
        this.semanticConflictService = new SemanticConflictService(this, containmentService,
                conflictService);
        this.temporalProvenanceService = new TemporalProvenanceService(this, decisionService, dppService);
        this.dppAssociations = new DppAssetAssociationService(dppService, containmentService);
    }

    /**
     * Submits supported lifecycle events and their acceptance decisions.
     * Semantic rejections are committed; malformed requests fail without a write.
     *
     * @param ctx transaction context
     * @param json JSON-serialized LifecycleEvent
     * @return the decision, serialized as JSON
     */
    public String submitEvent(Context ctx, String json) {
        LifecycleEvent event = JsonMapper.fromJson(json, LifecycleEvent.class);
        checkDocument(event);
        String operationKey = "OPERATION_ID:" + keyPart(event.getOperationId());

        String previousId = ctx.getStub().getStringState(operationKey);
        if (present(previousId)) {
            LifecycleEvent previous = read(ctx, previousId);
            // Automatically assigned clocks must not break retries of identical input.
            if (event.getVectorClock() == null || event.getVectorClock().isEmpty()) {
                event.setVectorClock(previous.getVectorClock());
            }
            if (!JsonMapper.toJson(previous).equals(JsonMapper.toJson(event))) {
                throw new IllegalArgumentException("operationId is already associated with another request.");
            }
            return JsonMapper.toJson(decisionService.read(ctx, decisionId(previousId)));
        }

        if (event.getType() != LifecycleEventType.CORRECT
                && event.getType() != LifecycleEventType.INSTALL
                && event.getType() != LifecycleEventType.REMOVE
                && event.getType() != LifecycleEventType.INSPECT
                && event.getType() != LifecycleEventType.REPAIR
                && event.getType() != LifecycleEventType.REUSE) {
            throw new IllegalArgumentException("Unsupported lifecycle event type: " + event.getType());
        }
        validateReferences(ctx, event);
        causalVersionService.prepare(ctx, event);
        SemanticConflictService.Analysis analysis = semanticConflictService.analyze(ctx, event);
        AcceptanceDecision decision;
        if (!analysis.conflictingIds().isEmpty()) {
            semanticConflictService.recordConflict(ctx, event, analysis);
            decision = rejected(event, "Incompatible concurrent lifecycle event(s): "
                    + String.join(", ", analysis.conflictingIds()));
        } else {
            String historicalConflict = (event.getType() == LifecycleEventType.INSTALL
                    || event.getType() == LifecycleEventType.REMOVE)
                    ? temporalProvenanceService.validateStructuralEvent(ctx, event) : null;
            if (historicalConflict != null) {
                decision = rejected(event, historicalConflict);
            } else {
                decision = switch (event.getType()) {
                case INSTALL -> install(ctx, event);
                case REMOVE -> remove(ctx, event);
                case INSPECT -> inspect(ctx, event);
                case REPAIR -> repair(ctx, event);
                case REUSE -> reuse(ctx, event);
                case CORRECT -> correct(ctx, event);
                default -> throw new IllegalArgumentException(
                        "Unsupported lifecycle event type in this version: " + event.getType());
                };
            }
        }

        create(ctx, JsonMapper.toJson(event));
        decisionService.create(ctx, JsonMapper.toJson(decision));
        if (event.getType() == LifecycleEventType.CORRECT
                && decision.getOutcome() == EventStatus.ACCEPTED) {
            temporalProvenanceService.recordCorrection(ctx, event);
        }
        ctx.getStub().putStringState(operationKey, event.getId());
        causalVersionService.record(ctx, event);
        if (decision.getOutcome() == EventStatus.ACCEPTED) {
            semanticConflictService.recordAccepted(ctx, event, analysis);
        }
        // Consumers must process only VALID transactions in committed Fabric blocks.
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("schemaVersion", 1);
        notification.put("txId", ctx.getStub().getTxId());
        notification.put("event", event);
        notification.put("decision", decision);
        ctx.getStub().setEvent("DPP_LIFECYCLE_COMMITTED",
                JsonMapper.toJson(notification).getBytes(StandardCharsets.UTF_8));
        return JsonMapper.toJson(decision);
    }

    /** Returns the latest effective-time state; historical commit cutoffs require an off-chain projection. */
    public String reconstructState(Context ctx, String dppId, String effectiveTime, long asOfPosition) {
        return temporalProvenanceService.reconstructState(ctx, dppId, effectiveTime, asOfPosition);
    }

    private AcceptanceDecision correct(Context ctx, LifecycleEvent event) {
        String invalidReason = temporalProvenanceService.validateCorrection(ctx, event);
        return invalidReason == null ? accepted(event, "Historical effective-time correction recorded.")
                : rejected(event, invalidReason);
    }

    private void checkDocument(LifecycleEvent event) {
        if (event == null || event.getDocType() != DocType.LIFECYCLE_EVENT
                || !present(event.getId()) || !present(event.getOperationId())
                || !present(event.getActorId()) || event.getType() == null
                || event.getEffectiveTime() == null || event.getIssuedAt() == null
                || event.getDppIds() == null || event.getDppIds().isEmpty()
                || event.getAffectedAssetIds() == null || event.getAffectedAssetIds().isEmpty()
                || event.getData() == null) {
            throw new IllegalArgumentException("Incomplete LifecycleEvent.");
        }
        for (String id : event.getDppIds()) {
            require(id, "dppId");
        }
        for (String id : event.getAffectedAssetIds()) {
            require(id, "affectedAssetId");
        }
        if (event.getParentEventIds() != null && event.getParentEventIds().contains(event.getId())) {
            throw new IllegalArgumentException("An event cannot be its own parent.");
        }
        if (event.getVectorClock() != null && event.getVectorClock().entrySet().stream()
                .anyMatch(e -> !present(e.getKey()) || e.getValue() == null || e.getValue() < 0)) {
            throw new IllegalArgumentException("Invalid vector clock entry.");
        }
    }

    private void validateReferences(Context ctx, LifecycleEvent event) {
        actorService.read(ctx, event.getActorId());
        for (String id : event.getDppIds()) {
            dppService.read(ctx, id);
        }
        for (String id : event.getAffectedAssetIds()) {
            assetService.read(ctx, id);
        }
        if (event.getParentEventIds() != null) {
            for (String id : event.getParentEventIds()) {
                read(ctx, require(id, "parentEventId"));
            }
        }
    }

    private AcceptanceDecision install(Context ctx, LifecycleEvent event) {
        LifecycleEventData data = event.getData();
        String component = require(data.getComponentId(), "componentId");
        String host = require(data.getHostId(), "hostId");
        String slot = require(data.getSlotId(), "slotId");
        requireAffected(event, component, host);
        if (component.equals(host)) {
            return rejected(event, "A component cannot contain itself.");
        }

        String componentKey = activeComponentKey(component);
        String slotKey = activeSlotKey(host, slot);
        String occupyingComponent = ctx.getStub().getStringState(componentKey);
        String occupyingSlot = ctx.getStub().getStringState(slotKey);
        if (present(occupyingComponent) || present(occupyingSlot)) {
            Set<String> related = new HashSet<>();
            related.add(event.getId());
            if (present(occupyingComponent)) related.add(occupyingComponent);
            if (present(occupyingSlot)) related.add(occupyingSlot);
            Conflict conflict = new Conflict();
            conflict.setId(event.getId());
            conflict.setType(ConflictType.STRUCTURAL);
            conflict.setStatus(ConflictStatus.PENDING);
            conflict.setEventIds(related);
            conflictService.create(ctx, JsonMapper.toJson(conflict));
            return rejected(event, "Component or host slot is already occupied.");
        }
        if (earlierThanLast(ctx, lastComponentKey(component), event.getEffectiveTime())
                || earlierThanLast(ctx, lastSlotKey(host, slot), event.getEffectiveTime())) {
            return rejected(event, "Retroactive installation requires the correction workflow.");
        }
        if (wouldCreateCycle(ctx, component, host)) {
            return rejected(event, "Installation would create a containment cycle.");
        }
        String previousStructuralTime = ctx.getStub().getStringState(lastComponentKey(component));
        if (present(previousStructuralTime)) {
            String reuseId = ctx.getStub().getStringState(reuseReadyKey(component));
            if (!present(reuseId)) {
                return rejected(event, "Previously installed component requires an accepted REUSE event.");
            }
            LifecycleEvent reuseEvent = read(ctx, reuseId);
            if (reuseEvent.getType() != LifecycleEventType.REUSE
                    || !event.getEffectiveTime().isAfter(reuseEvent.getEffectiveTime())) {
                return rejected(event, "Installation must follow the approved reuse time.");
            }
        }

        dppAssociations.validateInstall(ctx, event, component, host);

        ContainmentInterval interval = new ContainmentInterval();
        interval.setId(event.getId());
        interval.setComponentId(component);
        interval.setHostId(host);
        interval.setSlotId(slot);
        interval.setValidFrom(event.getEffectiveTime());
        containmentService.create(ctx, JsonMapper.toJson(interval));
        ctx.getStub().putStringState(componentKey, event.getId());
        ctx.getStub().putStringState(slotKey, event.getId());
        ctx.getStub().putStringState(lastComponentKey(component), event.getEffectiveTime().toString());
        ctx.getStub().putStringState(lastSlotKey(host, slot), event.getEffectiveTime().toString());
        TemporalProvenanceService.recordHostedComponent(ctx, host);
        ctx.getStub().delState(reuseReadyKey(component));
        return accepted(event, "Component installed.");
    }

    private AcceptanceDecision remove(Context ctx, LifecycleEvent event) {
        LifecycleEventData data = event.getData();
        String component = require(data.getComponentId(), "componentId");
        requireAffected(event, component);
        String componentKey = activeComponentKey(component);
        String installEventId = ctx.getStub().getStringState(componentKey);
        if (!present(installEventId)) {
            return rejected(event, "Component is not currently installed.");
        }
        ContainmentInterval interval = containmentService.read(ctx, installEventId);
        if (present(data.getHostId()) && !Objects.equals(data.getHostId(), interval.getHostId())) {
            return rejected(event, "Specified host does not match the active installation.");
        }
        if (present(data.getSlotId()) && !Objects.equals(data.getSlotId(), interval.getSlotId())) {
            return rejected(event, "Specified slot does not match the active installation.");
        }
        if (!event.getAffectedAssetIds().contains(interval.getHostId())) {
            throw new IllegalArgumentException("The active host must be in affectedAssetIds.");
        }
        if (!event.getEffectiveTime().isAfter(interval.getValidFrom())) {
            return rejected(event, "Removal must be later than installation.");
        }
        String slotKey = activeSlotKey(interval.getHostId(), interval.getSlotId());
        if (!Objects.equals(installEventId, ctx.getStub().getStringState(slotKey))) {
            throw new IllegalStateException("Active component and slot guards disagree.");
        }

        dppAssociations.validateRemove(ctx, event, read(ctx, installEventId),
                component, interval.getHostId());

        containmentService.close(ctx, interval.getId(), event.getEffectiveTime());
        ctx.getStub().delState(componentKey);
        ctx.getStub().delState(slotKey);
        ctx.getStub().putStringState(lastComponentKey(component), event.getEffectiveTime().toString());
        ctx.getStub().putStringState(lastSlotKey(interval.getHostId(), interval.getSlotId()),
                event.getEffectiveTime().toString());
        ctx.getStub().delState(reuseReadyKey(component));
        return accepted(event, "Component removed.");
    }

    private AcceptanceDecision inspect(Context ctx, LifecycleEvent event) {
        LifecycleEventData data = event.getData();
        String assetId = require(data.getAssetId(), "assetId");
        requireAffected(event, assetId);
        if (data.getInspectionResult() == null) {
            throw new IllegalArgumentException("inspectionResult is required.");
        }
        if (!afterRecordedTime(ctx, lastComponentKey(assetId), event.getEffectiveTime())) {
            return rejected(event, "Inspection cannot precede the last structural change.");
        }
        Inspection previous = latestInspection(ctx, assetId);
        if (previous != null && !event.getEffectiveTime().isAfter(previous.getInspectedAt())) {
            return rejected(event, "Inspection must be later than the previous inspection.");
        }
        String repairId = ctx.getStub().getStringState(lastRepairKey(assetId));
        if (present(repairId) && !event.getEffectiveTime().isAfter(read(ctx, repairId).getEffectiveTime())) {
            return rejected(event, "Inspection must be later than the last repair.");
        }
        String reuseId = ctx.getStub().getStringState(reuseReadyKey(assetId));
        if (present(reuseId) && !event.getEffectiveTime().isAfter(read(ctx, reuseId).getEffectiveTime())) {
            return rejected(event, "Inspection must be later than the reuse approval.");
        }

        Inspection inspection = new Inspection();
        inspection.setId(event.getId());
        inspection.setAssetId(assetId);
        inspection.setInspectedAt(event.getEffectiveTime());
        inspection.setResult(data.getInspectionResult());
        inspection.setEvidenceRef(data.getEvidenceRef());
        inspectionService.create(ctx, JsonMapper.toJson(inspection));
        ctx.getStub().putStringState(latestInspectionKey(assetId), event.getId());
        ctx.getStub().delState(reuseReadyKey(assetId));
        return accepted(event, "Inspection recorded.");
    }

    /** Records a repair after an adverse inspection; the asset must be detached. */
    private AcceptanceDecision repair(Context ctx, LifecycleEvent event) {
        String assetId = require(event.getData().getAssetId(), "assetId");
        requireAffected(event, assetId);
        if (!hasEvidence(event)) {
            throw new IllegalArgumentException("REPAIR requires an evidenceRef or evidenceRefs entry.");
        }
        if (present(ctx.getStub().getStringState(activeComponentKey(assetId)))) {
            return rejected(event, "Component must be removed before repair.");
        }
        Inspection inspection = latestInspection(ctx, assetId);
        if (inspection == null || (inspection.getResult() != InspectionResult.FAILED
                && inspection.getResult() != InspectionResult.REQUIRES_REPAIR)) {
            return rejected(event, "Repair requires a preceding FAILED or REQUIRES_REPAIR inspection.");
        }
        if (!event.getEffectiveTime().isAfter(inspection.getInspectedAt())
                || !afterRecordedTime(ctx, lastComponentKey(assetId), event.getEffectiveTime())) {
            return rejected(event, "Repair must follow the inspection and last structural change.");
        }
        String previousRepairId = ctx.getStub().getStringState(lastRepairKey(assetId));
        if (present(previousRepairId)
                && !inspection.getInspectedAt().isAfter(read(ctx, previousRepairId).getEffectiveTime())) {
            return rejected(event, "A repair was already recorded for this inspection.");
        }
        String reuseId = ctx.getStub().getStringState(reuseReadyKey(assetId));
        if (present(reuseId)) {
            return rejected(event, "An approved REUSE event is pending installation.");
        }

        ctx.getStub().putStringState(lastRepairKey(assetId), event.getId());
        return accepted(event, "Repair recorded; a new PASSED inspection is required for reuse.");
    }

    /** Approves reuse after an inspection confirms fitness following removal or repair. */
    private AcceptanceDecision reuse(Context ctx, LifecycleEvent event) {
        String assetId = require(event.getData().getAssetId(), "assetId");
        requireAffected(event, assetId);
        if (present(ctx.getStub().getStringState(activeComponentKey(assetId)))) {
            return rejected(event, "Component must be removed before reuse.");
        }
        String lastStructuralTime = ctx.getStub().getStringState(lastComponentKey(assetId));
        if (!present(lastStructuralTime)) {
            return rejected(event, "Component has no previous installation to reuse.");
        }
        if (present(ctx.getStub().getStringState(reuseReadyKey(assetId)))) {
            return rejected(event, "An accepted REUSE event is already awaiting installation.");
        }
        Inspection inspection = latestInspection(ctx, assetId);
        if (inspection == null || inspection.getResult() != InspectionResult.PASSED) {
            return rejected(event, "Reuse requires a PASSED inspection.");
        }
        if (!inspection.getInspectedAt().isAfter(Instant.parse(lastStructuralTime))
                || !event.getEffectiveTime().isAfter(inspection.getInspectedAt())) {
            return rejected(event, "Reuse must follow a PASSED inspection performed after removal.");
        }
        String lastRepairId = ctx.getStub().getStringState(lastRepairKey(assetId));
        if (present(lastRepairId)
                && !inspection.getInspectedAt().isAfter(read(ctx, lastRepairId).getEffectiveTime())) {
            return rejected(event, "A new PASSED inspection is required after repair.");
        }

        ctx.getStub().putStringState(reuseReadyKey(assetId), event.getId());
        return accepted(event, "Component approved for reuse.");
    }

    private Inspection latestInspection(Context ctx, String assetId) {
        String id = ctx.getStub().getStringState(latestInspectionKey(assetId));
        return present(id) ? inspectionService.read(ctx, id) : null;
    }

    private boolean afterRecordedTime(Context ctx, String key, Instant effectiveTime) {
        String previous = ctx.getStub().getStringState(key);
        return !present(previous) || effectiveTime.isAfter(Instant.parse(previous));
    }

    private boolean hasEvidence(LifecycleEvent event) {
        return present(event.getData().getEvidenceRef()) || (event.getEvidenceRefs() != null
                && event.getEvidenceRefs().stream().anyMatch(LifecycleEventService::present));
    }

    private boolean wouldCreateCycle(Context ctx, String component, String host) {
        Set<String> seen = new HashSet<>();
        String cursor = host;
        while (present(cursor)) {
            if (component.equals(cursor) || !seen.add(cursor)) {
                return true;
            }
            String parentEventId = ctx.getStub().getStringState(activeComponentKey(cursor));
            if (!present(parentEventId)) {
                return false;
            }
            cursor = containmentService.read(ctx, parentEventId).getHostId();
        }
        return false;
    }

    private boolean earlierThanLast(Context ctx, String key, Instant effectiveTime) {
        String previous = ctx.getStub().getStringState(key);
        return present(previous) && effectiveTime.isBefore(Instant.parse(previous));
    }

    private void requireAffected(LifecycleEvent event, String... ids) {
        for (String id : ids) {
            if (!event.getAffectedAssetIds().contains(id)) {
                throw new IllegalArgumentException(id + " must be included in affectedAssetIds.");
            }
        }
    }

    private AcceptanceDecision accepted(LifecycleEvent event, String reason) {
        return decision(event, EventStatus.ACCEPTED, reason);
    }

    private AcceptanceDecision rejected(LifecycleEvent event, String reason) {
        return decision(event, EventStatus.REJECTED, reason);
    }

    private AcceptanceDecision decision(LifecycleEvent event, EventStatus outcome, String reason) {
        AcceptanceDecision decision = new AcceptanceDecision();
        decision.setId(decisionId(event.getId()));
        decision.setEventId(event.getId());
        decision.setOutcome(outcome);
        decision.setPolicyRef(POLICY_REF);
        decision.setReason(reason);
        // A Fabric block position is not available during chaincode simulation.
        return decision;
    }

    private static String decisionId(String eventId) {
        return "decision:" + eventId;
    }

    private static String activeComponentKey(String id) {
        return "ACTIVE_COMPONENT:" + keyPart(id);
    }

    private static String activeSlotKey(String host, String slot) {
        return "ACTIVE_SLOT:" + keyPart(host) + ":" + keyPart(slot);
    }

    private static String lastComponentKey(String id) {
        return "LAST_COMPONENT_TIME:" + keyPart(id);
    }

    private static String latestInspectionKey(String id) {
        return "LATEST_INSPECTION:" + keyPart(id);
    }

    private static String lastRepairKey(String id) {
        return "LAST_REPAIR:" + keyPart(id);
    }

    private static String reuseReadyKey(String id) {
        return "REUSE_READY:" + keyPart(id);
    }

    private static String lastSlotKey(String host, String slot) {
        return "LAST_SLOT_TIME:" + keyPart(host) + ":" + keyPart(slot);
    }

    private static String keyPart(String id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                require(id, "ledger key field").getBytes(StandardCharsets.UTF_8));
    }

    private static String require(String value, String field) {
        if (!present(value) || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " must not be blank or contain NUL.");
        }
        return value;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
