package edu.tucn.dppprovver.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import edu.tucn.dppprovver.model.AcceptanceDecision;
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
@RequestMapping("/api/decisions")
@Tag(name = "AcceptanceDecision queries")
public class ReadAcceptanceDecisionController {
    private final ReadOnlyLedgerService service;

    public ReadAcceptanceDecisionController(ReadOnlyLedgerService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public AcceptanceDecision read(@PathVariable String id) {
        return service.read("readAcceptanceDecision", id, AcceptanceDecision.class);
    }

    @GetMapping
    public List<AcceptanceDecision> list() {
        return service.list("getAllAcceptanceDecisions", new TypeReference<List<AcceptanceDecision>>() {
        });
    }
}
