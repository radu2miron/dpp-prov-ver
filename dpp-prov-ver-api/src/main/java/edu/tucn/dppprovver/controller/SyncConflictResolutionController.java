package edu.tucn.dppprovver.controller;

import edu.tucn.dppprovver.model.Resolution;
import edu.tucn.dppprovver.model.SubmitResponse;
import edu.tucn.dppprovver.service.SyncConflictResolutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@RestController
@RequestMapping("/api/conflicts")
@Tag(name = "Conflict resolution")
public class SyncConflictResolutionController {
    private final SyncConflictResolutionService service;

    public SyncConflictResolutionController(SyncConflictResolutionService service) {
        this.service = service;
    }

    @PostMapping("/{id}/resolve")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a semantic conflict resolution",
            description = "ACCEPT_EVENT confirms an already accepted event; REJECT_EVENT confirms an already rejected event. No status reversal.")
    public SubmitResponse resolve(@PathVariable String id, @RequestBody Resolution body) {
        return service.resolve(id, body);
    }
}
