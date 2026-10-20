package edu.tucn.dppprovver.contract;

import edu.tucn.dppprovver.model.Conflict;
import edu.tucn.dppprovver.model.ConflictStatus;
import edu.tucn.dppprovver.model.DocType;
import edu.tucn.dppprovver.util.JsonMapper;
import org.hyperledger.fabric.contract.Context;

/** Persistence service for conflict documents and their resolved projection. */
public class ConflictService extends AbstractLedgerService<Conflict> {
    public ConflictService() {
        super(Conflict.class, DocType.CONFLICT, Conflict::getId, Conflict::getDocType);
    }

    /** Closes a pending conflict while retaining its immutable event references. */
    void markResolved(Context ctx, String conflictId, String resolutionId) {
        Conflict conflict = read(ctx, conflictId);
        if (conflict.getStatus() != ConflictStatus.PENDING || conflict.getResolutionId() != null) {
            throw new IllegalArgumentException("Conflict is not pending: " + conflictId);
        }
        conflict.setStatus(ConflictStatus.RESOLVED);
        conflict.setResolutionId(resolutionId);
        ctx.getStub().putStringState(DocType.CONFLICT.name() + ":" + conflictId,
                JsonMapper.toJson(conflict));
    }
}
