package edu.tucn.dppprovver.controller;

import edu.tucn.dppprovver.model.HistoricalState;
import edu.tucn.dppprovver.service.ReadOnlyLedgerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@RestController
@RequestMapping("/api/passports")
@Tag(name = "Temporal provenance")
public class HistoricalStateController {
    private final ReadOnlyLedgerService service;

    public HistoricalStateController(ReadOnlyLedgerService service) {
        this.service = service;
    }

    @GetMapping("/{id}/state")
    @Operation(summary = "Reconstruct the effective-time containment state",
            description = "Latest accepted ledger interpretation only (asOfPosition=0); not an as-known-at historical snapshot.")
    public HistoricalState state(@PathVariable String id,
                                 @RequestParam String effectiveTime,
                                 @RequestParam(defaultValue = "0") String asOfPosition) {
        return service.readState(id, effectiveTime, asOfPosition);
    }
}
