package edu.tucn.dppprovver.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import edu.tucn.dppprovver.model.Resolution;
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
@RequestMapping("/api/resolutions")
@Tag(name = "Resolution queries")
public class ReadResolutionController {
    private final ReadOnlyLedgerService service;

    public ReadResolutionController(ReadOnlyLedgerService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public Resolution read(@PathVariable String id) {
        return service.read("readResolution", id, Resolution.class);
    }

    @GetMapping
    public List<Resolution> list() {
        return service.list("getAllResolutions", new TypeReference<List<Resolution>>() {
        });
    }
}
