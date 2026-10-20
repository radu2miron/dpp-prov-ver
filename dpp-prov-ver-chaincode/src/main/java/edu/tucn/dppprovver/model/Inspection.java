package edu.tucn.dppprovver.model;

import java.time.Instant;
import lombok.Data;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@Data
public class Inspection {
    private DocType docType = DocType.INSPECTION;
    private String id;
    private Instant inspectedAt;
    private InspectionResult result;
    private String evidenceRef;
    private String assetId;
}
