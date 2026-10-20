package edu.tucn.dppprovver.model;

import java.time.Instant;

import lombok.Data;

/** Type-specific input for lifecycle operations. */
@Data
public class LifecycleEventData {
    private String componentId;
    private String hostId;
    private String slotId;
    private String assetId;
    private InspectionResult inspectionResult;
    private String evidenceRef;
    // CORRECT: accepted INSTALL/REMOVE event whose effective time is being corrected.
    private String targetEventId;
    private Instant correctedEffectiveTime;
}
