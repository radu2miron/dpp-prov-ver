package edu.tucn.dppprovver.contract;

import edu.tucn.dppprovver.model.DigitalProductPassport;
import edu.tucn.dppprovver.model.DocType;
import edu.tucn.dppprovver.util.JsonMapper;
import java.util.Objects;
import org.hyperledger.fabric.contract.Context;

/** Persistence service for DigitalProductPassport documents. */
public class DigitalProductPassportService extends AbstractLedgerService<DigitalProductPassport> {
    public DigitalProductPassportService() {
        super(DigitalProductPassport.class, DocType.DIGITAL_PRODUCT_PASSPORT, DigitalProductPassport::getId, DigitalProductPassport::getDocType);
    }

    /** Registers a passport only for an existing root asset. */
    @Override
    public void create(Context ctx, String json) {
        DigitalProductPassport passport = JsonMapper.fromJson(json, DigitalProductPassport.class);
        if (passport == null || passport.getRootAssetId() == null
                || passport.getRootAssetId().isBlank()) {
            throw new IllegalArgumentException("A DPP must reference a rootAssetId.");
        }
        new AssetService().read(ctx, passport.getRootAssetId());
        super.create(ctx, json);
    }

    /** Updates an existing mutable DigitalProductPassport document. */
    public void update(Context ctx, String updatedJson) {
        updateRecord(ctx, updatedJson);
    }

    @Override
    protected void validateUpdate(DigitalProductPassport current, DigitalProductPassport updated) {
        if (!Objects.equals(current.getRootAssetId(), updated.getRootAssetId())) {
            throw new IllegalArgumentException("The root asset of a DPP cannot be updated.");
        }
    }
}
