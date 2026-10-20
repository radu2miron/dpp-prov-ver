package edu.tucn.dppprovver.service;

import com.fasterxml.jackson.core.type.TypeReference;
import edu.tucn.dppprovver.model.HistoricalState;
import edu.tucn.dppprovver.model.TransactionRequest;
import edu.tucn.dppprovver.model.TxType;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@Service
public class ReadOnlyLedgerService {
    private final BlockchainProxy proxy;
    private final JsonMapper json;

    public ReadOnlyLedgerService(BlockchainProxy proxy, JsonMapper json) {
        this.proxy = proxy;
        this.json = json;
    }

    public <T> T read(String method, String id, Class<T> type) {
        return json.fromJson(proxy.execute(new TransactionRequest(TxType.READ, method, id)).payload(), type);
    }

    public <T> List<T> list(String method, TypeReference<List<T>> type) {
        return json.fromJson(proxy.execute(new TransactionRequest(TxType.READ, method)).payload(), type);
    }

    public HistoricalState state(String dppId, String effectiveTime) {
        return readState(dppId, effectiveTime, "0");
    }

    public HistoricalState readState(String dppId, String effectiveTime, String asOfPosition) {
        if (!"0".equals(asOfPosition)) throw new IllegalArgumentException(
                "Only asOfPosition=0 is supported by the chaincode; historical commit cutoffs need an off-chain projection");
        return json.fromJson(proxy.execute(new TransactionRequest(TxType.READ,
                "reconstructState", dppId, effectiveTime, asOfPosition)).payload(), HistoricalState.class);
    }
}
