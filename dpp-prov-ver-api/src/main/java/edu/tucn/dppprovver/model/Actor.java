package edu.tucn.dppprovver.model;

import lombok.Data;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@Data
public class Actor {
    private DocType docType = DocType.ACTOR;
    private String id;
    private String organizationId;
    private ActorRole role;
}
