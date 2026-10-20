package edu.tucn.dppprovver.service;

import edu.tucn.dppprovver.model.*;
import org.springframework.stereotype.Service;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@Service
public class SyncConflictResolutionService {
    private final BlockchainProxy proxy;
    private final JsonMapper json;

    public SyncConflictResolutionService(BlockchainProxy proxy, JsonMapper json) {
        this.proxy = proxy;
        this.json = json;
    }

    public SubmitResponse resolve(String conflictId, Resolution resolution) {
        if (resolution == null || conflictId == null || conflictId.isBlank())
            throw new IllegalArgumentException("Missing conflict ID or resolution");
        if (resolution.getConflictId() != null && !conflictId.equals(resolution.getConflictId()))
            throw new IllegalArgumentException("URL conflict ID differs from body conflictId");
        resolution.setConflictId(conflictId);
        GatewayResult r = proxy.execute(new TransactionRequest(TxType.SUBMIT, "resolveConflict",
                json.toJson(resolution)));
        return new SubmitResponse("Resolution committed", resolution.getId(), r.transactionId(),
                r.fabricStatus(), null, json.tree(r.payload()), r.durationMs());
    }
}
