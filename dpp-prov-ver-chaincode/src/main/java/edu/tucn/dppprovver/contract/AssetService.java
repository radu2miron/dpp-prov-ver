package edu.tucn.dppprovver.contract;

import edu.tucn.dppprovver.model.Asset;
import edu.tucn.dppprovver.model.DocType;
import edu.tucn.dppprovver.util.JsonMapper;
import java.util.Objects;
import org.hyperledger.fabric.contract.Context;

/** Persistence service for Asset documents. */
public class AssetService extends AbstractLedgerService<Asset> {
    public AssetService() {
        super(Asset.class, DocType.ASSET, Asset::getId, Asset::getDocType);
    }

    /**
     * Marks assets created by this chaincode version as having a known empty
     * structural history. Older ledger assets intentionally lack this marker.
     */
    @Override
    public void create(Context ctx, String json) {
        super.create(ctx, json);
        Asset asset = JsonMapper.fromJson(json, Asset.class);
        TemporalProvenanceService.recordNewAsset(ctx, asset.getId());
    }

    /** Updates an existing mutable Asset document. */
    public void update(Context ctx, String updatedJson) {
        updateRecord(ctx, updatedJson);
    }

    @Override
    protected void validateUpdate(Asset current, Asset updated) {
        if (!Objects.equals(current.getExternalId(), updated.getExternalId())
                || !Objects.equals(current.getIdentityPolicyRef(), updated.getIdentityPolicyRef())
                || !Objects.equals(current.getAssetType(), updated.getAssetType())) {
            throw new IllegalArgumentException("Asset identity fields cannot be updated.");
        }
    }
}
