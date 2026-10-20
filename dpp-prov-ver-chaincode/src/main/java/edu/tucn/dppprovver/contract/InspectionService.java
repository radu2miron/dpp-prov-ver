package edu.tucn.dppprovver.contract;

import edu.tucn.dppprovver.model.Inspection;
import edu.tucn.dppprovver.model.DocType;
import org.hyperledger.fabric.contract.Context;

/** Persistence service for Inspection documents. */
public class InspectionService extends AbstractLedgerService<Inspection> {
    public InspectionService() {
        super(Inspection.class, DocType.INSPECTION, Inspection::getId, Inspection::getDocType);
    }
}
