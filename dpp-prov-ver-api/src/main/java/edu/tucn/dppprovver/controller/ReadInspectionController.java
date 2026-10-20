package edu.tucn.dppprovver.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import edu.tucn.dppprovver.model.Inspection;
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
@RequestMapping("/api/inspections")
@Tag(name = "Inspection queries")
public class ReadInspectionController {
    private final ReadOnlyLedgerService service;

    public ReadInspectionController(ReadOnlyLedgerService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public Inspection read(@PathVariable String id) {
        return service.read("readInspection", id, Inspection.class);
    }

    @GetMapping
    public List<Inspection> list() {
        return service.list("getAllInspections", new TypeReference<List<Inspection>>() {
        });
    }
}
