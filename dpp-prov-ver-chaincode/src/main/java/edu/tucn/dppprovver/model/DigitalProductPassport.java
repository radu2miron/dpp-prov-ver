package edu.tucn.dppprovver.model;

import lombok.Data;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@Data
public class DigitalProductPassport {
    private DocType docType = DocType.DIGITAL_PRODUCT_PASSPORT;
    private String id;
    private String schemaVersion;
    // Exactly one root asset is described by this passport.
    private String rootAssetId;
}
