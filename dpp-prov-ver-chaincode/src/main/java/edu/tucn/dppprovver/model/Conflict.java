package edu.tucn.dppprovver.model;

import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Data;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@Data
public class Conflict {
    private DocType docType = DocType.CONFLICT;
    private String id;
    private ConflictType type;
    private ConflictStatus status;
    private String resolutionId;
    // Contains at least two event ids for a semantic conflict.
    private Set<String> eventIds = new LinkedHashSet<>();
}
