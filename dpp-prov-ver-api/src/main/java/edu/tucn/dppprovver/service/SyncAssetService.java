package edu.tucn.dppprovver.service;

import com.fasterxml.jackson.core.type.TypeReference;
import edu.tucn.dppprovver.model.Asset;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@Service
public class SyncAssetService extends LedgerService<Asset> {
    public SyncAssetService(BlockchainProxy proxy, JsonMapper mapper) {
        super(proxy, mapper, "Asset", Asset.class,
                new TypeReference<List<Asset>>() {
                }, Asset::getId, Asset::setId);
    }
}
