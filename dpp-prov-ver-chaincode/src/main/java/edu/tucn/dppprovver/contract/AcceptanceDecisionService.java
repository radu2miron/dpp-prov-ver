package edu.tucn.dppprovver.contract;

import edu.tucn.dppprovver.model.AcceptanceDecision;
import edu.tucn.dppprovver.model.DocType;
import org.hyperledger.fabric.contract.Context;

/** Persistence service for AcceptanceDecision documents. */
public class AcceptanceDecisionService extends AbstractLedgerService<AcceptanceDecision> {
    public AcceptanceDecisionService() {
        super(AcceptanceDecision.class, DocType.ACCEPTANCE_DECISION, AcceptanceDecision::getId, AcceptanceDecision::getDocType);
    }
}
