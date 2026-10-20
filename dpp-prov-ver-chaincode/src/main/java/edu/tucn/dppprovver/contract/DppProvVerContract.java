package edu.tucn.dppprovver.contract;

import edu.tucn.dppprovver.util.JsonMapper;
import org.hyperledger.fabric.contract.Context;
import org.hyperledger.fabric.contract.ContractInterface;
import org.hyperledger.fabric.contract.annotation.Contract;
import org.hyperledger.fabric.contract.annotation.Default;
import org.hyperledger.fabric.contract.annotation.Transaction;

/**
 * Fabric-facing façade for the domain document services.
 * Lifecycle validation, authorization, and invariant enforcement are added separately.
 */
@Contract(name = "DppProvVerContract")
@Default
public class DppProvVerContract implements ContractInterface {
    private final ActorService actorService = new ActorService();
    private final DigitalProductPassportService digitalProductPassportService = new DigitalProductPassportService();
    private final AssetService assetService = new AssetService();
    private final ContainmentIntervalService containmentIntervalService = new ContainmentIntervalService();
    private final InspectionService inspectionService = new InspectionService();
    private final AcceptanceDecisionService acceptanceDecisionService = new AcceptanceDecisionService();
    private final ConflictService conflictService = new ConflictService();
    private final ResolutionService resolutionService = new ResolutionService();
    private final LifecycleEventService lifecycleEventService = new LifecycleEventService(
            actorService, digitalProductPassportService, assetService,
            containmentIntervalService, inspectionService, acceptanceDecisionService, conflictService);

    private final ConflictResolutionService conflictResolutionService = new ConflictResolutionService(
            actorService, lifecycleEventService, conflictService, resolutionService,
            acceptanceDecisionService);

    /** Resolves a pending conflict without changing existing event decisions. */
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String resolveConflict(Context ctx, String resolutionJson) {
        return conflictResolutionService.resolve(ctx, resolutionJson);
    }

    /** Creates a Actor document. */
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public void createActor(Context ctx, String json) {
        actorService.create(ctx, json);
    }

    /** Updates a Actor document while preserving immutable identity fields. */
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public void updateActor(Context ctx, String json) {
        actorService.update(ctx, json);
    }

    /** Reads a Actor by its plain {@code id}. */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String readActor(Context ctx, String id) {
        return JsonMapper.toJson(actorService.read(ctx, id));
    }

    /** Lists all Actor documents (development use; unpaginated). */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String getAllActors(Context ctx) {
        return JsonMapper.toJson(actorService.readAll(ctx));
    }

    /** Creates a DigitalProductPassport document. */
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public void createDigitalProductPassport(Context ctx, String json) {
        digitalProductPassportService.create(ctx, json);
    }

    /** Updates a DigitalProductPassport document while preserving immutable identity fields. */
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public void updateDigitalProductPassport(Context ctx, String json) {
        digitalProductPassportService.update(ctx, json);
    }

    /** Reads a DigitalProductPassport by its plain {@code id}. */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String readDigitalProductPassport(Context ctx, String id) {
        return JsonMapper.toJson(digitalProductPassportService.read(ctx, id));
    }

    /** Lists all DigitalProductPassport documents (development use; unpaginated). */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String getAllDigitalProductPassports(Context ctx) {
        return JsonMapper.toJson(digitalProductPassportService.readAll(ctx));
    }

    /** Creates a Asset document. */
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public void createAsset(Context ctx, String json) {
        assetService.create(ctx, json);
    }

    /** Updates a Asset document while preserving immutable identity fields. */
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public void updateAsset(Context ctx, String json) {
        assetService.update(ctx, json);
    }

    /** Reads a Asset by its plain {@code id}. */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String readAsset(Context ctx, String id) {
        return JsonMapper.toJson(assetService.read(ctx, id));
    }

    /** Lists all Asset documents (development use; unpaginated). */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String getAllAssets(Context ctx) {
        return JsonMapper.toJson(assetService.readAll(ctx));
    }

    /** Processes a lifecycle event and returns the committed semantic decision on successful commit. */
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String submitEvent(Context ctx, String json) {
        return lifecycleEventService.submitEvent(ctx, json);
    }

    /** Reads the latest accepted containment state at effective time (asOfPosition must be "0"). */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String reconstructState(Context ctx, String dppId, String effectiveTime,
            String asOfPosition) {
        return lifecycleEventService.reconstructState(ctx, dppId, effectiveTime,
                Long.parseLong(asOfPosition));
    }

    /** Reads a LifecycleEvent by its plain {@code id}. */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String readLifecycleEvent(Context ctx, String id) {
        return JsonMapper.toJson(lifecycleEventService.read(ctx, id));
    }

    /** Lists all LifecycleEvent documents (development use; unpaginated). */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String getAllLifecycleEvents(Context ctx) {
        return JsonMapper.toJson(lifecycleEventService.readAll(ctx));
    }

    /** Reads a ContainmentInterval by its plain {@code id}. */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String readContainmentInterval(Context ctx, String id) {
        return JsonMapper.toJson(containmentIntervalService.read(ctx, id));
    }

    /** Lists all ContainmentInterval documents (development use; unpaginated). */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String getAllContainmentIntervals(Context ctx) {
        return JsonMapper.toJson(containmentIntervalService.readAll(ctx));
    }

    /** Reads a Inspection by its plain {@code id}. */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String readInspection(Context ctx, String id) {
        return JsonMapper.toJson(inspectionService.read(ctx, id));
    }

    /** Lists all Inspection documents (development use; unpaginated). */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String getAllInspections(Context ctx) {
        return JsonMapper.toJson(inspectionService.readAll(ctx));
    }

    /** Reads a AcceptanceDecision by its plain {@code id}. */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String readAcceptanceDecision(Context ctx, String id) {
        return JsonMapper.toJson(acceptanceDecisionService.read(ctx, id));
    }

    /** Lists all AcceptanceDecision documents (development use; unpaginated). */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String getAllAcceptanceDecisions(Context ctx) {
        return JsonMapper.toJson(acceptanceDecisionService.readAll(ctx));
    }

    /** Reads a Conflict by its plain {@code id}. */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String readConflict(Context ctx, String id) {
        return JsonMapper.toJson(conflictService.read(ctx, id));
    }

    /** Lists all Conflict documents (development use; unpaginated). */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String getAllConflicts(Context ctx) {
        return JsonMapper.toJson(conflictService.readAll(ctx));
    }

    /** Reads a Resolution by its plain {@code id}. */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String readResolution(Context ctx, String id) {
        return JsonMapper.toJson(resolutionService.read(ctx, id));
    }

    /** Lists all Resolution documents (development use; unpaginated). */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String getAllResolutions(Context ctx) {
        return JsonMapper.toJson(resolutionService.readAll(ctx));
    }

}
