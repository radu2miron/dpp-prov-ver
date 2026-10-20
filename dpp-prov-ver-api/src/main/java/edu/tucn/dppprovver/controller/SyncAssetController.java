package edu.tucn.dppprovver.controller;

import edu.tucn.dppprovver.model.Asset;
import edu.tucn.dppprovver.model.SubmitResponse;
import edu.tucn.dppprovver.service.SyncAssetService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@RestController
@RequestMapping("/api/assets")
@Tag(name = "Asset")
public class SyncAssetController {
    private final SyncAssetService service;

    public SyncAssetController(SyncAssetService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SubmitResponse create(@RequestBody Asset body) {
        return service.create(body);
    }

    @PutMapping("/{id}")
    public SubmitResponse update(@PathVariable String id, @RequestBody Asset body) {
        return service.update(id, body);
    }

    @GetMapping("/{id}")
    public Asset read(@PathVariable String id) {
        return service.read(id);
    }

    @GetMapping
    public List<Asset> list() {
        return service.list();
    }
}
