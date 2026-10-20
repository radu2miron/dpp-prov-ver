package edu.tucn.dppprovver.model;

import java.time.Instant;
import lombok.Data;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@Data
public class ContainmentInterval {
    private DocType docType = DocType.CONTAINMENT_INTERVAL;
    private String id;
    private String slotId;
    private Instant validFrom;
    // Null when the interval is open-ended.
    private Instant validTo;
    // References to Asset.id.
    private String hostId;
    private String componentId;
}
