package edu.tucn.dppprovver.service;

import com.fasterxml.jackson.core.type.TypeReference;
import edu.tucn.dppprovver.model.*;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@Service
public class SyncLifecycleEventService {
    private final BlockchainProxy blockchain;
    private final JsonMapper json;

    public SyncLifecycleEventService(BlockchainProxy blockchain, JsonMapper json) {
        this.blockchain = blockchain;
        this.json = json;
    }

    public SubmitResponse submit(LifecycleEvent event) {
        if (event == null || event.getId() == null || event.getId().isBlank()
                || event.getOperationId() == null || event.getOperationId().isBlank())
            throw new IllegalArgumentException("Explicit event id and operationId are required for reproducibility");
        GatewayResult r = blockchain.execute(new TransactionRequest(TxType.SUBMIT,
                "submitEvent", json.toJson(event)));
        AcceptanceDecision decision = json.fromJson(r.payload(), AcceptanceDecision.class);
        return new SubmitResponse("Lifecycle decision committed", event.getId(), r.transactionId(),
                r.fabricStatus(), decision.getOutcome().name(), json.tree(r.payload()), r.durationMs());
    }

    public LifecycleEvent read(String id) {
        return json.fromJson(blockchain.execute(new TransactionRequest(
                TxType.READ, "readLifecycleEvent", id)).payload(), LifecycleEvent.class);
    }

    public List<LifecycleEvent> list() {
        return json.fromJson(blockchain.execute(new TransactionRequest(
                        TxType.READ, "getAllLifecycleEvents")).payload(),
                new TypeReference<List<LifecycleEvent>>() {
                });
    }
}
