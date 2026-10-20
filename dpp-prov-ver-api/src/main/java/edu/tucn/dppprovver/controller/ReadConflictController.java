package edu.tucn.dppprovver.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import edu.tucn.dppprovver.model.Conflict;
import edu.tucn.dppprovver.service.ReadOnlyLedgerService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@RestController
@RequestMapping("/api/conflicts")
@Tag(name = "Conflict queries")
public class ReadConflictController {
    private final ReadOnlyLedgerService service;

    public ReadConflictController(ReadOnlyLedgerService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public Conflict read(@PathVariable String id) {
        return service.read("readConflict", id, Conflict.class);
    }

    @GetMapping
    public List<Conflict> list() {
        return service.list("getAllConflicts", new TypeReference<List<Conflict>>() {
        });
    }
}
