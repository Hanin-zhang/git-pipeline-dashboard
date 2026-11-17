package com.githublook.repo;

import com.githublook.repo.dto.CreateRepoRequest;
import com.githublook.repo.dto.RepoDto;
import com.githublook.repo.dto.UpdateRepoRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/repos")
@RequiredArgsConstructor
public class RepoController {

    private final RepoService repoService;

    @GetMapping
    public List<RepoDto> list() {
        return repoService.list();
    }

    @PostMapping
    public ResponseEntity<RepoDto> create(@Valid @RequestBody CreateRepoRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(repoService.create(request));
    }

    @PutMapping("/{id}")
    public RepoDto update(@PathVariable Long id, @Valid @RequestBody UpdateRepoRequest request) {
        return repoService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        repoService.assertNotLastRepo(id);
        repoService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
