package io.supportflow.customer.controller.bot;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.customer.dto.bot.AskBody;
import io.supportflow.customer.dto.bot.AskResult;
import io.supportflow.customer.dto.bot.CategoryBody;
import io.supportflow.customer.dto.bot.CategoryVO;
import io.supportflow.customer.dto.bot.ChunkVO;
import io.supportflow.customer.dto.bot.DocVO;
import io.supportflow.customer.dto.bot.DocumentBody;
import io.supportflow.customer.dto.bot.KbDetailVO;
import io.supportflow.customer.dto.bot.KbIndexRollbackOption;
import io.supportflow.customer.dto.bot.KbOverviewVO;
import io.supportflow.customer.dto.bot.KbSearchVO;
import io.supportflow.customer.dto.bot.SearchBody;
import io.supportflow.customer.dto.bot.StatusBody;
import io.supportflow.customer.service.bot.KbService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * 企业知识库：文档台账、上传索引、切片预览、检索测试。
 */
@RestController
@RequestMapping("/api/customer/kb")
public class KbController {

    private final KbService kbService;

    public KbController(KbService kbService) {
        this.kbService = kbService;
    }

    /**
     * 总览：文档数、已发布、切片总数、向量库是否就绪
     */
    @GetMapping("/overview")
    public ApiResponse<KbOverviewVO> overview() {
        return ApiResponse.ok(kbService.overview(LoginUserContext.require()));
    }

    /**
     * 文档列表
     */
    @GetMapping("/documents")
    public ApiResponse<List<DocVO>> documents(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(kbService.documents(user, categoryId, status, keyword));
    }

    /**
     * 文档详情（带回正文，供编辑框回显）
     */
    @GetMapping("/documents/{id}")
    public ApiResponse<KbDetailVO> detail(
            @PathVariable long id
    ) {
        return ApiResponse.ok(kbService.detail(LoginUserContext.require(), id));
    }

    /**
     * 切片预览：看"切块"这一步到底切成了什么样
     */
    @GetMapping("/documents/{id}/chunks")
    public ApiResponse<List<ChunkVO>> chunks(
            @PathVariable long id
    ) {
        return ApiResponse.ok(kbService.chunks(LoginUserContext.require(), id));
    }

    /**
     * 手工新建文档（正文直接索引）
     */
    @PostMapping("/documents")
    public ApiResponse<DocVO> create(
            @Valid @RequestBody DocumentBody body
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(kbService.create(
                user, body.title(), body.categoryId(), body.content(), body.summary()));
    }

    /**
     * 编辑文档（正文变了会自动重新索引）
     */
    @PutMapping("/documents/{id}")
    public ApiResponse<DocVO> update(
            @PathVariable long id,
            @RequestBody DocumentBody body
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(kbService.update(
                user, id, body.title(), body.categoryId(), body.content(), body.summary()));
    }

    /**
     * 上传文件建文档：文件进对象存储，内容由 AI 服务解析 + 切块 + 向量化
     */
    @PostMapping("/documents/upload")
    public ApiResponse<DocVO> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            @RequestParam(value = "title", required = false) String title
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(kbService.upload(user, file, categoryId, title));
    }

    /**
     * 重新索引（换了切块参数、或上次索引失败重试）
     */
    @PostMapping("/documents/{id}/reindex")
    public ApiResponse<DocVO> reindex(
            @PathVariable long id
    ) {
        return ApiResponse.ok(kbService.reindex(LoginUserContext.require(), id));
    }

    @GetMapping("/documents/{id}/index/previous")
    public ApiResponse<KbIndexRollbackOption> rollbackOption(@PathVariable long id) {
        return ApiResponse.ok(kbService.rollbackOption(LoginUserContext.require(), id));
    }

    @PostMapping("/documents/{id}/index/rollback")
    public ApiResponse<DocVO> rollbackIndex(@PathVariable long id, @Valid @RequestBody IndexRollbackBody body) {
        return ApiResponse.ok(kbService.rollbackIndex(LoginUserContext.require(), id, body.versionId()));
    }

    public record IndexRollbackBody(@NotNull UUID versionId) {}

    /**
     * 发布 / 下线
     */
    @PostMapping("/documents/{id}/status")
    public ApiResponse<DocVO> changeStatus(
            @PathVariable long id,
            @RequestBody StatusBody body
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(kbService.changeStatus(user, id, body.status()));
    }

    /**
     * 删除文档（连带清掉切片）
     */
    @DeleteMapping("/documents/{id}")
    public ApiResponse<Void> delete(
            @PathVariable long id
    ) {
        kbService.delete(LoginUserContext.require(), id);
        return ApiResponse.ok(null);
    }

    /**
     * 分类列表
     */
    @GetMapping("/categories")
    public ApiResponse<List<CategoryVO>> categories() {
        return ApiResponse.ok(kbService.categories(LoginUserContext.require()));
    }

    /**
     * 新建分类
     */
    @PostMapping("/categories")
    public ApiResponse<CategoryVO> createCategory(
            @Valid @RequestBody CategoryBody body
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(kbService.createCategory(user, body.name(), body.parentId(), body.sortNo()));
    }

    /**
     * 检索测试：问一句，看命中的切片与相似度
     */
    @PostMapping("/search")
    public ApiResponse<KbSearchVO> search(
            @Valid @RequestBody SearchBody body
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(kbService.search(user, body.query(), body.topK()));
    }

    /**
     * 知识问答：让 AI 查资料后回答并标出处
     */
    @PostMapping("/ask")
    public ApiResponse<AskResult> ask(
            @Valid @RequestBody AskBody body
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(kbService.ask(user, body.question(), body.topK()));
    }
}
