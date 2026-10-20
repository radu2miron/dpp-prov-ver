package edu.tucn.dppprovver.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import edu.tucn.dppprovver.model.ContainmentInterval;
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
@RequestMapping("/api/containment-intervals")
@Tag(name = "ContainmentInterval queries")
public class ReadContainmentIntervalController {
    private final ReadOnlyLedgerService service;

    public ReadContainmentIntervalController(ReadOnlyLedgerService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public ContainmentInterval read(@PathVariable String id) {
        return service.read("readContainmentInterval", id, ContainmentInterval.class);
    }

    @GetMapping
    public List<ContainmentInterval> list() {
        return service.list("getAllContainmentIntervals", new TypeReference<List<ContainmentInterval>>() {
        });
    }
}
