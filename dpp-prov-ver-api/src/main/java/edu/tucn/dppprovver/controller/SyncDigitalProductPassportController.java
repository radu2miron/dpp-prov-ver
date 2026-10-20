package edu.tucn.dppprovver.controller;

import edu.tucn.dppprovver.model.DigitalProductPassport;
import edu.tucn.dppprovver.model.SubmitResponse;
import edu.tucn.dppprovver.service.SyncDigitalProductPassportService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@RestController
@RequestMapping("/api/passports")
@Tag(name = "DigitalProductPassport")
public class SyncDigitalProductPassportController {
    private final SyncDigitalProductPassportService service;

    public SyncDigitalProductPassportController(SyncDigitalProductPassportService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SubmitResponse create(@RequestBody DigitalProductPassport body) {
        return service.create(body);
    }

    @PutMapping("/{id}")
    public SubmitResponse update(@PathVariable String id, @RequestBody DigitalProductPassport body) {
        return service.update(id, body);
    }

    @GetMapping("/{id}")
    public DigitalProductPassport read(@PathVariable String id) {
        return service.read(id);
    }

    @GetMapping
    public List<DigitalProductPassport> list() {
        return service.list();
    }
}
