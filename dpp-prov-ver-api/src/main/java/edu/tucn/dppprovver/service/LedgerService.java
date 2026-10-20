package edu.tucn.dppprovver.service;

import com.fasterxml.jackson.core.type.TypeReference;
import edu.tucn.dppprovver.model.GatewayResult;
import edu.tucn.dppprovver.model.SubmitResponse;
import edu.tucn.dppprovver.model.TransactionRequest;
import edu.tucn.dppprovver.model.TxType;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Shared typed adapter for Fabric-backed documents; no off-chain mutation.
 *
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
public abstract class LedgerService<T> {
    protected final BlockchainProxy blockchain;
    protected final JsonMapper json;
    private final String createFn, updateFn, readFn, allFn;
    private final Class<T> type;
    private final TypeReference<List<T>> listType;
    private final Function<T, String> getId;
    private final java.util.function.BiConsumer<T, String> setId;

    protected LedgerService(BlockchainProxy blockchain, JsonMapper json, String domain,
                            Class<T> type, TypeReference<List<T>> listType,
                            Function<T, String> getId, java.util.function.BiConsumer<T, String> setId) {
        this.blockchain = blockchain;
        this.json = json;
        this.type = type;
        this.listType = listType;
        this.getId = getId;
        this.setId = setId;
        this.createFn = "create" + domain;
        this.updateFn = "update" + domain;
        this.readFn = "read" + domain;
        this.allFn = "getAll" + domain + "s";
    }

    public SubmitResponse create(T entity) {
        if (entity == null) throw new IllegalArgumentException("Body must not be null");
        String id = getId.apply(entity);
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
            setId.accept(entity, id);
        }
        return submit(createFn, entity, "Created", id);
    }

    public SubmitResponse update(String id, T entity) {
        if (entity == null || id == null || id.isBlank()) throw new IllegalArgumentException("Missing ID or body");
        if (getId.apply(entity) != null && !id.equals(getId.apply(entity)))
            throw new IllegalArgumentException("URL ID differs from body ID");
        setId.accept(entity, id);
        return submit(updateFn, entity, "Updated", id);
    }

    private SubmitResponse submit(String fn, T entity, String message, String id) {
        GatewayResult r = blockchain.execute(new TransactionRequest(TxType.SUBMIT, fn, json.toJson(entity)));
        return new SubmitResponse(message, id, r.transactionId(), r.fabricStatus(), null,
                json.tree(r.payload()), r.durationMs());
    }

    public T read(String id) {
        String s = blockchain.execute(new TransactionRequest(TxType.READ, readFn, id)).payload();
        return json.fromJson(s, type);
    }

    public List<T> list() {
        String s = blockchain.execute(new TransactionRequest(TxType.READ, allFn)).payload();
        return json.fromJson(s, listType);
    }
}
