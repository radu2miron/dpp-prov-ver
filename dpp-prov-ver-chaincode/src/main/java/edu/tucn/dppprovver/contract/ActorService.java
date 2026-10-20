package edu.tucn.dppprovver.contract;

import edu.tucn.dppprovver.model.Actor;
import edu.tucn.dppprovver.model.DocType;
import org.hyperledger.fabric.contract.Context;

/** Persistence service for Actor documents. */
public class ActorService extends AbstractLedgerService<Actor> {
    public ActorService() {
        super(Actor.class, DocType.ACTOR, Actor::getId, Actor::getDocType);
    }

    /** Updates an existing mutable Actor document. */
    public void update(Context ctx, String updatedJson) {
        updateRecord(ctx, updatedJson);
    }
}
