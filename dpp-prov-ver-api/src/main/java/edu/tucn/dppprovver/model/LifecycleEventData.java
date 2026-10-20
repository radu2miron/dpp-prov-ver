package edu.tucn.dppprovver.model;

import lombok.Data;

import java.time.Instant;

/**
 * Type-specific input for lifecycle operations.
 *
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
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
