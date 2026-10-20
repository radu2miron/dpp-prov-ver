package edu.tucn.dppprovver.contract;

import edu.tucn.dppprovver.model.DocType;
import edu.tucn.dppprovver.util.JsonMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.hyperledger.fabric.contract.Context;
import org.hyperledger.fabric.shim.ledger.KeyValue;
import org.hyperledger.fabric.shim.ledger.QueryResultsIterator;

/**
 * Shared, type-safe persistence operations for Fabric world-state documents.
 * This is a preliminary persistence layer, not lifecycle invariant validation.
 *
 * @param <T> the stored domain type
 */
abstract class AbstractLedgerService<T> {
    private final Class<T> entityClass;
    private final DocType docType;
    private final Function<T, String> idExtractor;
    private final Function<T, DocType> typeExtractor;

    protected AbstractLedgerService(Class<T> entityClass, DocType docType,
            Function<T, String> idExtractor, Function<T, DocType> typeExtractor) {
        this.entityClass = entityClass;
        this.docType = docType;
        this.idExtractor = idExtractor;
        this.typeExtractor = typeExtractor;
    }

    /** Persists a new document, rejecting duplicate IDs within its type. */
    public void create(Context ctx, String json) {
        T value = parseAndCheck(json);
        String key = ledgerKey(idExtractor.apply(value));
        if (exists(ctx, key)) {
            throw new IllegalArgumentException(docType + " with ID " + idExtractor.apply(value) + " already exists.");
        }
        ctx.getStub().putStringState(key, JsonMapper.toJson(value));
    }

    /** Returns one domain object identified by its plain {{@code id}}. */
    public T read(Context ctx, String id) {
        String key = ledgerKey(id);
        String json = ctx.getStub().getStringState(key);
        if (json == null || json.isEmpty()) {
            throw new IllegalArgumentException(docType + " with ID " + id + " does not exist.");
        }
        return JsonMapper.fromJson(json, entityClass);
    }

    /** Lists the documents of this type; intended for small datasets and development. */
    public List<T> readAll(Context ctx) {
        List<T> documents = new ArrayList<>();
        String prefix = docType.name() + ":";
        try (QueryResultsIterator<KeyValue> results = ctx.getStub().getStateByRange(prefix, prefix + "\uffff")) {
            for (KeyValue entry : results) {
                documents.add(JsonMapper.fromJson(entry.getStringValue(), entityClass));
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to list " + docType + " documents.", exception);
        }
        return documents;
    }

    /** Replaces a mutable document; subclasses expose this only where appropriate. */
    protected void updateRecord(Context ctx, String json) {
        T updated = parseAndCheck(json);
        String id = idExtractor.apply(updated);
        T current = read(ctx, id);
        validateUpdate(current, updated);
        ctx.getStub().putStringState(ledgerKey(id), JsonMapper.toJson(updated));
    }

    /** Optional restrictions on mutable record fields. */
    protected void validateUpdate(T current, T updated) {
    }

    private T parseAndCheck(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Document JSON must not be blank.");
        }
        T value = JsonMapper.fromJson(json, entityClass);
        if (value == null || typeExtractor.apply(value) != docType) {
            throw new IllegalArgumentException("Incorrect or missing docType; expected " + docType + ".");
        }
        ledgerKey(idExtractor.apply(value));
        return value;
    }

    private boolean exists(Context ctx, String key) {
        String current = ctx.getStub().getStringState(key);
        return current != null && !current.isEmpty();
    }

    private String ledgerKey(String id) {
        if (id == null || id.isBlank() || id.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("id must be nonblank and cannot contain NUL.");
        }
        return docType.name() + ":" + id;
    }
}
