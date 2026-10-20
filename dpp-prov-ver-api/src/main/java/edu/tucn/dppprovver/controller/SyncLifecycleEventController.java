package edu.tucn.dppprovver.controller;

import edu.tucn.dppprovver.model.LifecycleEvent;
import edu.tucn.dppprovver.model.SubmitResponse;
import edu.tucn.dppprovver.service.SyncLifecycleEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@RestController
@RequestMapping("/api/events")
@Tag(name = "Lifecycle events")
public class SyncLifecycleEventController {
    private final SyncLifecycleEventService service;

    public SyncLifecycleEventController(SyncLifecycleEventService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Atomically submit an INSTALL, REMOVE, INSPECT, REPAIR, REUSE or CORRECT event",
            description = "Fabric VALID commit does not imply semantic ACCEPTED: inspect semanticOutcome.")
    public SubmitResponse submit(@RequestBody LifecycleEvent body) {
        return service.submit(body);
    }

    @GetMapping("/{id}")
    public LifecycleEvent read(@PathVariable String id) {
        return service.read(id);
    }

    @GetMapping
    public List<LifecycleEvent> list() {
        return service.list();
    }
}
