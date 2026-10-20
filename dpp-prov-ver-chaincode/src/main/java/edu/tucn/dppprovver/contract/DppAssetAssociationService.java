package edu.tucn.dppprovver.contract;

import edu.tucn.dppprovver.model.ContainmentInterval;
import edu.tucn.dppprovver.model.DigitalProductPassport;
import edu.tucn.dppprovver.model.LifecycleEvent;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.hyperledger.fabric.contract.Context;

/** Validates the passport scope of accepted structural lifecycle operations. */
final class DppAssetAssociationService {
    private final DigitalProductPassportService passports;
    private final ContainmentIntervalService intervals;

    DppAssetAssociationService(DigitalProductPassportService passports,
            ContainmentIntervalService intervals) {
        this.passports = passports;
        this.intervals = intervals;
    }

    /** An INSTALL must identify the passport of the host's current root asset. */
    void validateInstall(Context ctx, LifecycleEvent event, String componentId, String hostId) {
        String root = rootOf(ctx, hostId);
        boolean hasHostPassport = false;
        for (String id : event.getDppIds()) {
            DigitalProductPassport passport = passports.read(ctx, id);
            String passportRoot = passport.getRootAssetId();
            if (root.equals(passportRoot)) {
                hasHostPassport = true;
            } else if (!componentId.equals(passportRoot)) {
                throw new IllegalArgumentException("Unrelated DPP " + id + " in INSTALL event.");
            }
        }
        if (!hasHostPassport) {
            throw new IllegalArgumentException("INSTALL must reference the host root's DPP.");
        }
    }

    /** A REMOVE retains original DPP attribution, including after host relocation. */
    void validateRemove(Context ctx, LifecycleEvent event, LifecycleEvent installation,
            String componentId, String hostId) {
        if (!event.getDppIds().containsAll(installation.getDppIds())) {
            throw new IllegalArgumentException("REMOVE must preserve the INSTALL event's DPP references.");
        }
        String root = rootOf(ctx, hostId);
        boolean hasHostPassport = false;
        for (String id : event.getDppIds()) {
            String passportRoot = passports.read(ctx, id).getRootAssetId();
            if (root.equals(passportRoot)) {
                hasHostPassport = true;
            } else if (!componentId.equals(passportRoot)
                    && !installation.getDppIds().contains(id)) {
                throw new IllegalArgumentException("Unrelated DPP " + id + " in REMOVE event.");
            }
        }
        if (!hasHostPassport) {
            throw new IllegalArgumentException("REMOVE must reference the current host root's DPP.");
        }
    }

    private String rootOf(Context ctx, String assetId) {
        Set<String> visited = new HashSet<>();
        String current = assetId;
        while (visited.add(current)) {
            String id = ctx.getStub().getStringState("ACTIVE_COMPONENT:" + encode(current));
            if (id == null || id.isBlank()) return current;
            ContainmentInterval edge = intervals.read(ctx, id);
            if (!current.equals(edge.getComponentId())) {
                throw new IllegalStateException("Inconsistent active component association.");
            }
            current = edge.getHostId();
            if (current == null || current.isBlank()) {
                throw new IllegalStateException("Active containment interval has no host.");
            }
        }
        throw new IllegalStateException("Cycle in active asset containment.");
    }

    private static String encode(String id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(id.getBytes(StandardCharsets.UTF_8));
    }
}
