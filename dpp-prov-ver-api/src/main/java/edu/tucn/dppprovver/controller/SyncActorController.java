package edu.tucn.dppprovver.controller;

import edu.tucn.dppprovver.model.Actor;
import edu.tucn.dppprovver.model.SubmitResponse;
import edu.tucn.dppprovver.service.SyncActorService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@RestController
@RequestMapping("/api/actors")
@Tag(name = "Actor")
public class SyncActorController {
    private final SyncActorService service;

    public SyncActorController(SyncActorService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SubmitResponse create(@RequestBody Actor body) {
        return service.create(body);
    }

    @PutMapping("/{id}")
    public SubmitResponse update(@PathVariable String id, @RequestBody Actor body) {
        return service.update(id, body);
    }

    @GetMapping("/{id}")
    public Actor read(@PathVariable String id) {
        return service.read(id);
    }

    @GetMapping
    public List<Actor> list() {
        return service.list();
    }
}
