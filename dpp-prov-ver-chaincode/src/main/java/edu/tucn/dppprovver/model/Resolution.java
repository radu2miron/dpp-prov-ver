package edu.tucn.dppprovver.model;

import java.time.Instant;
import lombok.Data;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@Data
public class Resolution {
    private DocType docType = DocType.RESOLUTION;
    private String id;
    private ResolutionAction action;
    private Instant decidedAt;
    private String policyRef;
    private String reason;
    private String conflictId;
    private String selectedEventId;
    private String actorId;
}
