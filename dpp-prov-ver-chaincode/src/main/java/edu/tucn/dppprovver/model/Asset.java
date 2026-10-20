package edu.tucn.dppprovver.model;

import lombok.Data;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@Data
public class Asset {
    private DocType docType = DocType.ASSET;
    private String id;
    private String externalId;
    private String assetType;
    private String identityPolicyRef;
}
