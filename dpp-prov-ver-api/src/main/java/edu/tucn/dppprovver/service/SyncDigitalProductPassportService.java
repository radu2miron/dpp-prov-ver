package edu.tucn.dppprovver.service;

import com.fasterxml.jackson.core.type.TypeReference;
import edu.tucn.dppprovver.model.DigitalProductPassport;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@Service
public class SyncDigitalProductPassportService extends LedgerService<DigitalProductPassport> {
    public SyncDigitalProductPassportService(BlockchainProxy proxy, JsonMapper mapper) {
        super(proxy, mapper, "DigitalProductPassport", DigitalProductPassport.class,
                new TypeReference<List<DigitalProductPassport>>() {
                }, DigitalProductPassport::getId, DigitalProductPassport::setId);
    }
}
