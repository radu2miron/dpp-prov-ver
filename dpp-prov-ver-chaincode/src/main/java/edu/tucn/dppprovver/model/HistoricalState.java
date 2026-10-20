package edu.tucn.dppprovver.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** A read-only latest effective-time snapshot (no on-chain commit position). */
@Data
public class HistoricalState {
    private String dppId;
    private Instant effectiveTime;
    private Long asOfPosition;
    private Long latestPosition;
    private List<ContainmentInterval> activeIntervals = new ArrayList<>();
    private List<ContainmentInterval> intervals = new ArrayList<>();
}
