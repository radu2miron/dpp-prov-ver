package edu.tucn.dppprovver.model;

import lombok.Data;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@Data
public class AcceptanceDecision {
    private DocType docType = DocType.ACCEPTANCE_DECISION;
    private String id;
    private EventStatus outcome;
    // Assigned by an off-chain block-event projection after a VALID commit.
    private Long ledgerPosition;
    private String policyRef;
    private String reason;
    private String eventId;
    // Optional: decision resulting from a Resolution.
    private String resolutionId;
}
