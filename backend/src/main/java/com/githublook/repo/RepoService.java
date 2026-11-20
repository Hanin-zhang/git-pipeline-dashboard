package com.githublook.repo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.githublook.common.config.AppProperties;
import com.githublook.common.error.AppException;
import com.githublook.common.error.ErrorCode;
import com.githublook.common.time.TimeMapper;
import com.githublook.repo.dto.CreateRepoRequest;
import com.githublook.repo.dto.RepoDto;
import com.githublook.repo.dto.UpdateRepoRequest;
import com.githublook.repo.mapper.MonitoredRepoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RepoService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final MonitoredRepoMapper repoMapper;
    private final AppProperties props;
    private final TimeMapper time;

    public List<RepoDto> list() {
        return repoMapper.selectList(new LambdaQueryWrapper<MonitoredRepo>()
                        .orderByAsc(MonitoredRepo::getFullName))
                .stream().map(RepoDto::from).toList();
    }

    public List<String> activeFullNames() {
        return repoMapper.selectList(new LambdaQueryWrapper<MonitoredRepo>()
                        .eq(MonitoredRepo::getEnabled, true)
                        .orderByAsc(MonitoredRepo::getFullName))
                .stream().map(MonitoredRepo::getFullName).toList();
    }

    public Optional<MonitoredRepo> findByFullName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(repoMapper.selectOne(new LambdaQueryWrapper<MonitoredRepo>()
                .eq(MonitoredRepo::getFullName, fullName)
                .last("LIMIT 1")));
    }

    /**
     * Per-repo secret wins; otherwise the global fallback secret applies.
     */
    public String resolveSecret(String fullName) {
        return findByFullName(fullName)
                .map(MonitoredRepo::getWebhookSecret)
                .filter(s -> s != null && !s.isBlank())
                .orElse(props.getWebhook().getDefaultSecret());
    }

    @Transactional
    public MonitoredRepo registerIfAbsent(String owner, String name, String defaultBranch) {
        String fullName = owner + "/" + name;
        Optional<MonitoredRepo> existing = findByFullName(fullName);
        if (existing.isPresent()) {
            return existing.get();
        }
        MonitoredRepo repo = new MonitoredRepo();
        repo.setOwner(owner);
        repo.setName(name);
        repo.setFullName(fullName);
        repo.setDefaultBranch(defaultBranch);
        repo.setWebhookSecret(props.getWebhook().getDefaultSecret());
        repo.setEnabled(true);
        repo.setCreatedAt(time.now());
        repoMapper.insert(repo);
        log.info("auto_registered_repo repo={}", fullName);
        return repo;
    }

    @Transactional
    public RepoDto create(CreateRepoRequest request) {
        String fullName = request.fullName().trim();
        if (findByFullName(fullName).isPresent()) {
            throw AppException.conflict("仓库 " + fullName + " 已接入");
        }
        int slash = fullName.indexOf('/');
        MonitoredRepo repo = new MonitoredRepo();
        repo.setOwner(fullName.substring(0, slash));
        repo.setName(fullName.substring(slash + 1));
        repo.setFullName(fullName);
        repo.setDefaultBranch(request.defaultBranch() == null || request.defaultBranch().isBlank()
                ? "main" : request.defaultBranch().trim());
        repo.setWebhookSecret(request.secret() == null || request.secret().isBlank()
                ? generateSecret() : request.secret().trim());
        repo.setEnabled(true);
        repo.setCreatedAt(time.now());
        repoMapper.insert(repo);
        return RepoDto.from(repo);
    }

    @Transactional
    public RepoDto update(Long id, UpdateRepoRequest request) {
        MonitoredRepo repo = require(id);
        if (request.defaultBranch() != null && !request.defaultBranch().isBlank()) {
            repo.setDefaultBranch(request.defaultBranch().trim());
        }
        if (request.enabled() != null) {
            repo.setEnabled(request.enabled());
        }
        if (request.secret() != null && !request.secret().isBlank()) {
            repo.setWebhookSecret(request.secret().trim());
        }
        repoMapper.updateById(repo);
        return RepoDto.from(repo);
    }

    @Transactional
    public void delete(Long id) {
        MonitoredRepo repo = require(id);
        repoMapper.deleteById(id);
        log.info("removed_repo repo={}", repo.getFullName());
    }

    public MonitoredRepo require(Long id) {
        MonitoredRepo repo = repoMapper.selectById(id);
        if (repo == null) {
            throw AppException.notFound("仓库");
        }
        return repo;
    }

    public String generateSecret() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return "ghl_" + HexFormat.of().formatHex(bytes);
    }

    public void assertNotLastRepo(Long id) {
        Long count = repoMapper.selectCount(new LambdaQueryWrapper<MonitoredRepo>()
                .ne(MonitoredRepo::getId, id));
        if (count == null || count == 0) {
            throw new AppException(ErrorCode.CONFLICT, "至少需要保留一个接入仓库");
        }
    }
}
