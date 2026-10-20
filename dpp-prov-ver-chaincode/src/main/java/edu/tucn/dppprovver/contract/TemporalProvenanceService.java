package edu.tucn.dppprovver.contract;

import edu.tucn.dppprovver.model.AcceptanceDecision;
import edu.tucn.dppprovver.model.ContainmentInterval;
import edu.tucn.dppprovver.model.EventStatus;
import edu.tucn.dppprovver.model.HistoricalState;
import edu.tucn.dppprovver.model.LifecycleEvent;
import edu.tucn.dppprovver.model.LifecycleEventType;
import edu.tucn.dppprovver.util.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.hyperledger.fabric.contract.Context;

/** Validates temporal corrections and reconstructs the latest accepted effective-time history. */
public final class TemporalProvenanceService {
    private static final String CORRECTION_ACTIVE_KEY = "DPP_HISTORICAL_CORRECTIONS_PRESENT";

    private final LifecycleEventService events;
    private final AcceptanceDecisionService decisions;
    private final DigitalProductPassportService passports;

    public TemporalProvenanceService(LifecycleEventService events,
            AcceptanceDecisionService decisions, DigitalProductPassportService passports) {
        this.events = events;
        this.decisions = decisions;
        this.passports = passports;
    }

    /** Checks an INSTALL/REMOVE effective-time correction without changing existing assertions. */
    String validateCorrection(Context ctx, LifecycleEvent correction) {
        if (correction.getData() == null || blank(correction.getData().getTargetEventId())
                || correction.getData().getCorrectedEffectiveTime() == null) {
            throw new IllegalArgumentException("CORRECT requires targetEventId and correctedEffectiveTime.");
        }
        String targetId = correction.getData().getTargetEventId();
        LifecycleEvent target = events.read(ctx, targetId);
        if (target.getType() != LifecycleEventType.INSTALL
                && target.getType() != LifecycleEventType.REMOVE) {
            throw new IllegalArgumentException("Only accepted INSTALL/REMOVE effective times may be corrected.");
        }
        if (decisions.read(ctx, "decision:" + targetId).getOutcome() != EventStatus.ACCEPTED) {
            throw new IllegalArgumentException("Correction target must be ACCEPTED.");
        }
        if (correction.getParentEventIds() == null
                || !correction.getParentEventIds().contains(targetId)) {
            throw new IllegalArgumentException("CORRECT must causally reference its target event.");
        }
        if (!correction.getDppIds().containsAll(target.getDppIds())
                || !correction.getAffectedAssetIds().containsAll(target.getAffectedAssetIds())) {
            throw new IllegalArgumentException("CORRECT must cover target DPPs and affected assets.");
        }
        String head = ctx.getStub().getStringState(correctionHeadKey(targetId));
        if (!blank(head) && !correction.getParentEventIds().contains(head)) {
            throw new IllegalArgumentException("CORRECT must causally reference the previous correction.");
        }
        Instant current = blank(head) ? target.getEffectiveTime()
                : events.read(ctx, head).getData().getCorrectedEffectiveTime();
        if (current.equals(correction.getData().getCorrectedEffectiveTime())) {
            throw new IllegalArgumentException("Correction does not change the effective time.");
        }
        if (correction.getData().getCorrectedEffectiveTime().isAfter(correction.getIssuedAt())) {
            throw new IllegalArgumentException("Corrected effective time cannot be after issuance.");
        }
        try {
            List<Entry> entries = acceptedEntries(ctx);
            Map<String, Instant> overrides = effectiveOverrides(ctx, entries);
            overrides.put(targetId, correction.getData().getCorrectedEffectiveTime());
            replay(entries, overrides);
        } catch (InvalidHistory exception) {
            return exception.getMessage();
        }
        return null;
    }

    /** Rejects later structural operations that would contradict the corrected history. */
    String validateStructuralEvent(Context ctx, LifecycleEvent candidate) {
        if (!"1".equals(ctx.getStub().getStringState(CORRECTION_ACTIVE_KEY))) return null;
        try {
            List<Entry> entries = acceptedEntries(ctx);
            entries.add(new Entry(candidate));
            replay(entries, effectiveOverrides(ctx, entries));
        } catch (InvalidHistory exception) {
            return exception.getMessage();
        }
        return null;
    }

    /** Updates only the auxiliary latest-correction pointer, preserving both original events. */
    void recordCorrection(Context ctx, LifecycleEvent correction) {
        ctx.getStub().putStringState(
                correctionHeadKey(correction.getData().getTargetEventId()), correction.getId());
        ctx.getStub().putStringState(CORRECTION_ACTIVE_KEY, "1");
    }

    /**
     * Reconstructs the latest accepted history at the requested effective time.
     * Position 0 means latest; committed historical cutoffs require a block-event projection.
     */
    public String reconstructState(Context ctx, String dppId, String time, long asOfPosition) {
        if (asOfPosition != 0L) {
            throw new IllegalArgumentException("Historical as-of commit positions are not available "
                    + "inside chaincode. Use the off-chain Fabric block-event projection; "
                    + "pass 0 for the latest accepted state.");
        }
        Instant tau = Instant.parse(time);
        String rootAssetId = passports.read(ctx, dppId).getRootAssetId();
        List<Entry> entries = acceptedEntries(ctx);
        List<ContainmentInterval> history = replay(entries, effectiveOverrides(ctx, entries));
        HistoricalState state = new HistoricalState();
        state.setDppId(dppId);
        state.setEffectiveTime(tau);
        // Real block/transaction position is assigned only after commit.
        for (ContainmentInterval interval : history) {
            if (!interval.getValidFrom().isAfter(tau)
                    && belongedToRoot(interval, rootAssetId, history, tau)) {
                state.getIntervals().add(interval);
                if ((interval.getValidTo() == null || tau.isBefore(interval.getValidTo()))
                        && belongsToRootAt(interval, rootAssetId, history, tau)) {
                    state.getActiveIntervals().add(interval);
                }
            }
        }
        return JsonMapper.toJson(state);
    }

    /** Reads all accepted events; only a post-commit projection can filter by Fabric block position. */
    private List<Entry> acceptedEntries(Context ctx) {
        List<Entry> found = new ArrayList<>();
        for (LifecycleEvent event : events.readAll(ctx)) {
            AcceptanceDecision decision = decisions.read(ctx, "decision:" + event.getId());
            if (decision.getOutcome() == EventStatus.ACCEPTED) {
                found.add(new Entry(event));
            }
        }
        found.sort(Comparator.comparing(e -> e.event().getId()));
        return found;
    }

    /** A historical interval belongs to a DPP if its host was in the root's subtree at any overlapping time. */
    private static boolean belongedToRoot(ContainmentInterval interval, String root,
            List<ContainmentInterval> history, Instant tau) {
        if (belongsToRootAt(interval, root, history, interval.getValidFrom())) return true;
        for (ContainmentInterval other : history) {
            Instant candidate = other.getValidFrom();
            if (!candidate.isBefore(interval.getValidFrom()) && !candidate.isAfter(tau)
                    && (interval.getValidTo() == null || candidate.isBefore(interval.getValidTo()))
                    && belongsToRootAt(interval, root, history, candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean belongsToRootAt(ContainmentInterval interval, String root,
            List<ContainmentInterval> history, Instant time) {
        if (interval.getValidFrom().isAfter(time)
                || (interval.getValidTo() != null && !time.isBefore(interval.getValidTo()))) {
            return false;
        }
        String ancestor = interval.getHostId();
        Set<String> visited = new HashSet<>();
        while (ancestor != null && visited.add(ancestor)) {
            if (ancestor.equals(root)) return true;
            String current = ancestor;
            ancestor = null;
            for (ContainmentInterval edge : history) {
                if (current.equals(edge.getComponentId())
                        && !edge.getValidFrom().isAfter(time)
                        && (edge.getValidTo() == null || time.isBefore(edge.getValidTo()))) {
                    ancestor = edge.getHostId();
                    break;
                }
            }
        }
        return false;
    }

    /** Uses the current correction head, not an inferred order of world-state documents. */
    private Map<String, Instant> effectiveOverrides(Context ctx, List<Entry> entries) {
        Map<String, Instant> overrides = new HashMap<>();
        for (Entry entry : entries) {
            LifecycleEvent correction = entry.event();
            if (correction.getType() != LifecycleEventType.CORRECT) continue;
            String target = correction.getData().getTargetEventId();
            if (correction.getId().equals(ctx.getStub().getStringState(correctionHeadKey(target)))) {
                overrides.put(target, correction.getData().getCorrectedEffectiveTime());
            }
        }
        return overrides;
    }

    /** Replays accepted structural events in effective-time order and checks occupancy/cycles. */
    private List<ContainmentInterval> replay(List<Entry> entries, Map<String, Instant> overrides) {
        List<Entry> structural = entries.stream()
                .filter(e -> e.event().getType() == LifecycleEventType.INSTALL
                        || e.event().getType() == LifecycleEventType.REMOVE)
                .sorted(Comparator.comparing((Entry e) -> overrides.getOrDefault(
                        e.event().getId(), e.event().getEffectiveTime()))
                        .thenComparingInt(e -> e.event().getType() == LifecycleEventType.REMOVE ? 0 : 1)
                        .thenComparing(e -> e.event().getId()))
                .toList();
        Map<String, ContainmentInterval> byComponent = new LinkedHashMap<>();
        Map<String, ContainmentInterval> bySlot = new HashMap<>();
        List<ContainmentInterval> history = new ArrayList<>();
        for (Entry entry : structural) {
            LifecycleEvent event = entry.event();
            Instant effective = overrides.getOrDefault(event.getId(), event.getEffectiveTime());
            String component = event.getData().getComponentId();
            if (blank(component)) throw new InvalidHistory("Missing historical componentId.");
            if (event.getType() == LifecycleEventType.INSTALL) {
                String host = event.getData().getHostId();
                String slot = event.getData().getSlotId();
                if (blank(host) || blank(slot) || component.equals(host)
                        || byComponent.containsKey(component)
                        || bySlot.containsKey(slotKey(host, slot))
                        || createsCycle(component, host, byComponent)) {
                    throw new InvalidHistory("Corrected history would violate containment exclusivity or acyclicity.");
                }
                ContainmentInterval interval = new ContainmentInterval();
                interval.setId(event.getId());
                interval.setComponentId(component);
                interval.setHostId(host);
                interval.setSlotId(slot);
                interval.setValidFrom(effective);
                history.add(interval);
                byComponent.put(component, interval);
                bySlot.put(slotKey(host, slot), interval);
            } else {
                ContainmentInterval active = byComponent.get(component);
                if (active == null || !effective.isAfter(active.getValidFrom())
                        || (!blank(event.getData().getHostId())
                                && !event.getData().getHostId().equals(active.getHostId()))
                        || (!blank(event.getData().getSlotId())
                                && !event.getData().getSlotId().equals(active.getSlotId()))) {
                    throw new InvalidHistory("Corrected history would invalidate a removal interval.");
                }
                active.setValidTo(effective);
                byComponent.remove(component);
                bySlot.remove(slotKey(active.getHostId(), active.getSlotId()));
            }
        }
        for (Entry entry : entries) {
            LifecycleEvent e = entry.event();
            if (e.getType() == LifecycleEventType.REUSE || e.getType() == LifecycleEventType.REPAIR) {
                String asset = e.getData().getAssetId();
                if (blank(asset)) continue;
                for (ContainmentInterval interval : history) {
                    if (asset.equals(interval.getComponentId())
                            && !e.getEffectiveTime().isBefore(interval.getValidFrom())
                            && (interval.getValidTo() == null
                                    || e.getEffectiveTime().isBefore(interval.getValidTo()))) {
                        throw new InvalidHistory("Corrected history would place REPAIR/REUSE while installed.");
                    }
                }
            }
        }
        return history;
    }

    private static boolean createsCycle(String component, String host,
            Map<String, ContainmentInterval> active) {
        Set<String> visited = new HashSet<>();
        String parent = host;
        while (!blank(parent)) {
            if (parent.equals(component) || !visited.add(parent)) return true;
            ContainmentInterval edge = active.get(parent);
            if (edge == null) return false;
            parent = edge.getHostId();
        }
        return false;
    }

    private static String slotKey(String host, String slot) {
        return host.length() + ":" + host + ":" + slot;
    }

    private static String correctionHeadKey(String id) {
        return "CORRECTION_HEAD:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(id.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private record Entry(LifecycleEvent event) { }
    private static final class InvalidHistory extends IllegalArgumentException {
        private InvalidHistory(String message) { super(message); }
    }
}
