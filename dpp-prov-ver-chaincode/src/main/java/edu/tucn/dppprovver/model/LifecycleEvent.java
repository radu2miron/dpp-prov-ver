package edu.tucn.dppprovver.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Data;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@Data
public class LifecycleEvent {
    private DocType docType = DocType.LIFECYCLE_EVENT;
    private String id;
    private String operationId;
    private LifecycleEventType type;
    private Instant effectiveTime;
    private Instant issuedAt;
    // Actor ids are String keys (the Actor class has a String id).
    private Map<String, Long> vectorClock = new LinkedHashMap<>();
    private Set<String> parentEventIds = new LinkedHashSet<>();
    private List<String> evidenceRefs = new ArrayList<>();
    private String signature;
    private LifecycleEventData data;
    // UML relationships stored as identifier references.
    private String actorId;
    private Set<String> dppIds = new LinkedHashSet<>();
    private Set<String> affectedAssetIds = new LinkedHashSet<>();
}
