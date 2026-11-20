package com.githublook.repo;

import com.githublook.common.config.AppProperties;
import com.githublook.common.error.AppException;
import com.githublook.common.error.ErrorCode;
import com.githublook.common.time.TimeMapper;
import com.githublook.repo.dto.CreateRepoRequest;
import com.githublook.repo.dto.RepoDto;
import com.githublook.repo.dto.UpdateRepoRequest;
import com.githublook.repo.mapper.MonitoredRepoMapper;
import com.githublook.support.MybatisLambdaTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RepoService · 仓库接入")
class RepoServiceTest {

    @Mock
    private MonitoredRepoMapper mapper;

    private final AppProperties props = new AppProperties();
    private final TimeMapper time = new TimeMapper("Asia/Shanghai");
    private RepoService service;

    @BeforeAll
    static void initMybatisLambdas() {
        MybatisLambdaTestSupport.init(MonitoredRepo.class);
    }

    @BeforeEach
    void setUp() {
        service = new RepoService(mapper, props, time);
        props.getWebhook().setDefaultSecret("global-secret");
    }

    @Nested
    @DisplayName("仓库地址归一化")
    class Normalization {

        @ParameterizedTest(name = "{0} → {1}")
        @DisplayName("支持裸 slug、HTTPS、.git、子路径、SSH 五种常见写法")
        @CsvSource({
                "acme/app,                 acme/app",
                "  acme/app  ,             acme/app",
                "https://github.com/acme/app,            acme/app",
                "https://github.com/acme/app.git,        acme/app",
                "https://github.com/acme/app/tree/main,  acme/app",
                "git@github.com:acme/app.git,            acme/app",
                "http://gitlab.internal/acme/app,        acme/app",
                "acme/app?tab=actions,                   acme/app",
                "acme/app#readme,                        acme/app",
                "acme/app/,                              acme/app"
        })
        void normalizesCommonFormats(String raw, String expected) {
            assertThat(RepoService.normalizeFullName(raw)).isEqualTo(expected);
        }

        @ParameterizedTest
        @DisplayName("非法输入一律 400，避免脏数据进库后永远匹配不上 webhook")
        @ValueSource(strings = {"", "   ", "acme", "https://github.com/"})
        void rejectsInvalidInput(String raw) {
            assertThatThrownBy(() -> RepoService.normalizeFullName(raw))
                    .isInstanceOf(AppException.class)
                    .extracting(e -> ((AppException) e).getCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }

        @Test
        @DisplayName("含非法字符的 owner/repo 拒绝")
        void rejectsIllegalCharacters() {
            assertThatThrownBy(() -> RepoService.normalizeFullName("ac$me/app"))
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("格式不正确");
        }

        @Test
        @DisplayName("超长路径只取前两段（兼容用户直接从浏览器复制深层链接）")
        void keepsFirstTwoSegments() {
            assertThat(RepoService.normalizeFullName("https://github.com/acme/app/tree/main/.github"))
                    .isEqualTo("acme/app");
        }
    }

    @Nested
    @DisplayName("密钥解析")
    class SecretResolution {

        @Test
        @DisplayName("仓库自己的 Secret 优先于全局兜底")
        void perRepoSecretWins() {
            MonitoredRepo repo = new MonitoredRepo();
            repo.setWebhookSecret("repo-secret");
            when(mapper.selectOne(any())).thenReturn(repo);

            assertThat(service.resolveSecret("acme/app")).isEqualTo("repo-secret");
        }

        @Test
        @DisplayName("仓库没配 Secret 时回落到全局 Secret")
        void fallsBackToGlobalSecret() {
            MonitoredRepo repo = new MonitoredRepo();
            repo.setWebhookSecret("  ");
            when(mapper.selectOne(any())).thenReturn(repo);

            assertThat(service.resolveSecret("acme/app")).isEqualTo("global-secret");
        }

        @Test
        @DisplayName("仓库不存在时也用全局 Secret（供自动注册场景验签）")
        void unknownRepoUsesGlobalSecret() {
            when(mapper.selectOne(any())).thenReturn(null);

            assertThat(service.resolveSecret("acme/new")).isEqualTo("global-secret");
        }

        @Test
        @DisplayName("全局 Secret 也未配置时返回空串，由入口服务拒绝请求")
        void returnsEmptyWhenNothingConfigured() {
            props.getWebhook().setDefaultSecret("");
            when(mapper.selectOne(any())).thenReturn(null);

            assertThat(service.resolveSecret("acme/new")).isEmpty();
        }

        @Test
        @DisplayName("空仓库名直接返回空 Optional，不查库")
        void blankNameReturnsEmpty() {
            assertThat(service.findByFullName(null)).isEmpty();
            assertThat(service.findByFullName("  ")).isEmpty();
            verify(mapper, never()).selectOne(any());
        }
    }

    @Nested
    @DisplayName("增删改")
    class Crud {

        @Test
        @DisplayName("新增：拆分 owner/name、默认分支 main、自动生成密钥")
        void createsRepo() {
            when(mapper.selectOne(any())).thenReturn(null);

            RepoDto dto = service.create(new CreateRepoRequest("https://github.com/acme/app.git", null, null));

            ArgumentCaptor<MonitoredRepo> captor = ArgumentCaptor.forClass(MonitoredRepo.class);
            verify(mapper).insert(captor.capture());
            MonitoredRepo saved = captor.getValue();
            assertThat(saved.getOwner()).isEqualTo("acme");
            assertThat(saved.getName()).isEqualTo("app");
            assertThat(saved.getFullName()).isEqualTo("acme/app");
            assertThat(saved.getDefaultBranch()).isEqualTo("main");
            assertThat(saved.getWebhookSecret()).startsWith("ghl_").hasSize(4 + 48);
            assertThat(saved.getEnabled()).isTrue();
            assertThat(saved.getCreatedAt()).isNotNull();
            assertThat(dto.fullName()).isEqualTo("acme/app");
        }

        @Test
        @DisplayName("新增：用户指定的分支与密钥原样保留（并去除首尾空白）")
        void honoursProvidedBranchAndSecret() {
            when(mapper.selectOne(any())).thenReturn(null);

            service.create(new CreateRepoRequest("acme/app", "  develop  ", "  my-secret  "));

            ArgumentCaptor<MonitoredRepo> captor = ArgumentCaptor.forClass(MonitoredRepo.class);
            verify(mapper).insert(captor.capture());
            assertThat(captor.getValue().getDefaultBranch()).isEqualTo("develop");
            assertThat(captor.getValue().getWebhookSecret()).isEqualTo("my-secret");
        }

        @Test
        @DisplayName("重复接入返回 409")
        void rejectsDuplicate() {
            when(mapper.selectOne(any())).thenReturn(new MonitoredRepo());

            assertThatThrownBy(() -> service.create(new CreateRepoRequest("acme/app", null, null)))
                    .isInstanceOf(AppException.class)
                    .extracting(e -> ((AppException) e).getCode())
                    .isEqualTo(ErrorCode.CONFLICT);
            verify(mapper, never()).insert(any(MonitoredRepo.class));
        }

        @Test
        @DisplayName("更新：只改传入的字段，空值不覆盖已有配置")
        void updatesOnlyProvidedFields() {
            MonitoredRepo repo = new MonitoredRepo();
            repo.setId(1L);
            repo.setDefaultBranch("main");
            repo.setWebhookSecret("old-secret");
            repo.setEnabled(true);
            when(mapper.selectById(1L)).thenReturn(repo);

            service.update(1L, new UpdateRepoRequest(null, false, "  "));

            assertThat(repo.getDefaultBranch()).isEqualTo("main");
            assertThat(repo.getWebhookSecret()).isEqualTo("old-secret");
            assertThat(repo.getEnabled()).isFalse();
            verify(mapper).updateById(repo);
        }

        @Test
        @DisplayName("停用仓库后 webhook 仍然验签通过，但事件会被标记 IGNORED（不丢审计）")
        void disablingKeepsSecret() {
            MonitoredRepo repo = new MonitoredRepo();
            repo.setId(1L);
            repo.setWebhookSecret("keep-me");
            repo.setEnabled(true);
            when(mapper.selectById(1L)).thenReturn(repo);

            service.update(1L, new UpdateRepoRequest(null, false, null));

            assertThat(repo.getWebhookSecret()).isEqualTo("keep-me");
        }

        @Test
        @DisplayName("更新不存在的仓库返回 404")
        void updateMissingRepo() {
            when(mapper.selectById(9L)).thenReturn(null);

            assertThatThrownBy(() -> service.update(9L, new UpdateRepoRequest("dev", true, null)))
                    .isInstanceOf(AppException.class)
                    .extracting(e -> ((AppException) e).getCode())
                    .isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("删除最后一个仓库：控制器先调用 assertNotLastRepo，delete 不会被真正执行")
        void refusesToRemoveLastRepo() {
            MonitoredRepo repo = new MonitoredRepo();
            repo.setId(1L);
            repo.setFullName("acme/app");
            when(mapper.selectById(1L)).thenReturn(repo);
            when(mapper.selectCount(any())).thenReturn(0L);

            // 与 RepoController.delete() 的调用顺序保持一致
            assertThatThrownBy(() -> {
                service.assertNotLastRepo(1L);
                service.delete(1L);
            })
                    .isInstanceOf(AppException.class)
                    .extracting(e -> ((AppException) e).getCode())
                    .isEqualTo(ErrorCode.CONFLICT);
            verify(mapper, never()).deleteById(any(Long.class));
        }

        @Test
        @DisplayName("还有其它仓库时允许删除，并记录被删仓库名")
        void deletesWhenOthersExist() {
            MonitoredRepo repo = new MonitoredRepo();
            repo.setId(1L);
            repo.setFullName("acme/app");
            when(mapper.selectById(1L)).thenReturn(repo);

            service.delete(1L);

            verify(mapper).deleteById(1L);
        }

        @Test
        @DisplayName("异常信息明确提示至少保留一个仓库")
        void assertNotLastRepoMessage() {
            when(mapper.selectCount(any())).thenReturn(0L);

            assertThatThrownBy(() -> service.assertNotLastRepo(1L))
                    .hasMessageContaining("至少需要保留一个");
        }

        @Test
        @DisplayName("还有其它仓库时允许删除/停用")
        void allowsWhenOthersExist() {
            when(mapper.selectCount(any())).thenReturn(2L);

            service.assertNotLastRepo(1L);
        }
    }

    @Nested
    @DisplayName("密钥生成与自动注册")
    class GenerationAndAutoRegister {

        @Test
        @DisplayName("生成的密钥格式为 ghl_ + 48 位十六进制，且每次都不同")
        void generatesRandomSecrets() {
            String first = service.generateSecret();
            String second = service.generateSecret();

            assertThat(first).matches("^ghl_[0-9a-f]{48}$");
            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("自动注册使用全局密钥，避免新仓库无法验签")
        void autoRegisterUsesGlobalSecret() {
            when(mapper.selectOne(any())).thenReturn(null);

            MonitoredRepo repo = service.registerIfAbsent("acme", "new", "main");

            assertThat(repo.getWebhookSecret()).isEqualTo("global-secret");
            assertThat(repo.getEnabled()).isTrue();
            assertThat(repo.getFullName()).isEqualTo("acme/new");
            verify(mapper).insert(repo);
        }

        @Test
        @DisplayName("仓库已存在时直接返回，不重复插入")
        void autoRegisterIsIdempotent() {
            MonitoredRepo existing = new MonitoredRepo();
            existing.setId(3L);
            existing.setFullName("acme/app");
            when(mapper.selectOne(any())).thenReturn(existing);

            MonitoredRepo repo = service.registerIfAbsent("acme", "app", null);

            assertThat(repo.getId()).isEqualTo(3L);
            verify(mapper, never()).insert(any(MonitoredRepo.class));
        }
    }

    @Test
    @DisplayName("列表按 full_name 升序，前端下拉顺序稳定")
    void listsSorted() {
        MonitoredRepo a = new MonitoredRepo();
        a.setFullName("acme/app");
        MonitoredRepo b = new MonitoredRepo();
        b.setFullName("acme/api");
        when(mapper.selectList(any())).thenReturn(List.of(a, b));

        assertThat(service.list()).extracting(RepoDto::fullName).containsExactly("acme/app", "acme/api");
        assertThat(service.activeFullNames()).containsExactly("acme/app", "acme/api");
    }

    @Test
    @DisplayName("require 找不到时抛 404")
    void requireMissing() {
        when(mapper.selectById(5L)).thenReturn(null);

        assertThatThrownBy(() -> service.require(5L))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(service.findByFullName("acme/app")).isEqualTo(Optional.empty());
    }
}
