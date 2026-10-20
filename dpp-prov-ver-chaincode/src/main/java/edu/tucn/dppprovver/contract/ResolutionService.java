package edu.tucn.dppprovver.contract;

import edu.tucn.dppprovver.model.Resolution;
import edu.tucn.dppprovver.model.DocType;
import org.hyperledger.fabric.contract.Context;

/** Persistence service for Resolution documents. */
public class ResolutionService extends AbstractLedgerService<Resolution> {
    public ResolutionService() {
        super(Resolution.class, DocType.RESOLUTION, Resolution::getId, Resolution::getDocType);
    }
}
