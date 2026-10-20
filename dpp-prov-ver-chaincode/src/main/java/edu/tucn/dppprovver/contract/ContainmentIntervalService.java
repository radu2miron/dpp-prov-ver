package edu.tucn.dppprovver.contract;

import edu.tucn.dppprovver.model.ContainmentInterval;
import edu.tucn.dppprovver.model.DocType;
import edu.tucn.dppprovver.util.JsonMapper;
import java.time.Instant;
import org.hyperledger.fabric.contract.Context;

/** Persistence of containment intervals and their materialized end times. */
public class ContainmentIntervalService extends AbstractLedgerService<ContainmentInterval> {
    public ContainmentIntervalService() {
        super(ContainmentInterval.class, DocType.CONTAINMENT_INTERVAL,
                ContainmentInterval::getId, ContainmentInterval::getDocType);
    }

    /** Closes an interval without changing its identity or starting assertion. */
    void close(Context ctx, String id, Instant validTo) {
        ContainmentInterval interval = read(ctx, id);
        if (interval.getValidTo() != null || interval.getValidFrom() == null
                || validTo == null || !validTo.isAfter(interval.getValidFrom())) {
            throw new IllegalArgumentException("Invalid containment interval closing time.");
        }
        interval.setValidTo(validTo);
        ctx.getStub().putStringState(DocType.CONTAINMENT_INTERVAL + ":" + id,
                JsonMapper.toJson(interval));
    }
}
