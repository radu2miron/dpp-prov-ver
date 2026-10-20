package edu.tucn.dppprovver.service;

import com.fasterxml.jackson.core.type.TypeReference;
import edu.tucn.dppprovver.model.Actor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@Service
public class SyncActorService extends LedgerService<Actor> {
    public SyncActorService(BlockchainProxy proxy, JsonMapper mapper) {
        super(proxy, mapper, "Actor", Actor.class,
                new TypeReference<List<Actor>>() {
                }, Actor::getId, Actor::setId);
    }
}
