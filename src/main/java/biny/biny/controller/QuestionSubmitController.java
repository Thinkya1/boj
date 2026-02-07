package biny.biny.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import biny.biny.common.BaseResponse;
import biny.biny.common.ErrorCode;
import biny.biny.common.ResultUtils;
import biny.biny.exception.BusinessException;
import biny.biny.model.dto.questionsubmit.QuestionSubmitAddRequest;
import biny.biny.model.dto.questionsubmit.QuestionSubmitQueryRequest;
import biny.biny.model.entity.QuestionSubmit;
import biny.biny.model.entity.User;
import biny.biny.model.vo.QuestionSubmitVO;
import biny.biny.service.QuestionSubmitService;
import biny.biny.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

/**
 * 题目提交接口
 *
 * @author biny
 */
@RestController
@RequestMapping("/question_submit")
@Slf4j
public class QuestionSubmitController {

    private final QuestionSubmitService questionSubmitService;

    private final UserService userService;

    public QuestionSubmitController(QuestionSubmitService questionSubmitService,
                                    UserService userService) {
        this.questionSubmitService = questionSubmitService;
        this.userService = userService;
    }

    /**
     * 提交题目
     *
     * @param questionSubmitAddRequest
     * @param request
     * @return 提交记录的 id
     */
    @PostMapping("/")
    public BaseResponse<String> doQuestionSubmit(@RequestBody QuestionSubmitAddRequest questionSubmitAddRequest,
            HttpServletRequest request) {
        if (questionSubmitAddRequest == null
                || questionSubmitAddRequest.getQuestionId() == null
                || questionSubmitAddRequest.getQuestionId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 登录才能点赞
        final User loginUser = userService.getLoginUser(request);
        long questionSubmitId = questionSubmitService.doQuestionSubmit(questionSubmitAddRequest, loginUser);
        // 前端 JS number 无法精确表示 long（雪花 id），这里返回 string 避免轮询时 id 精度丢失导致“判题中卡死”
        return ResultUtils.success(String.valueOf(questionSubmitId));
    }

    /**
     * 分页获取题目提交列表（除了管理员外，普通用户只能看到非答案、提交代码等公开信息）
     *
     * @param questionSubmitQueryRequest
     * @param request
     * @return
     */
    @PostMapping("/list/page")
    public BaseResponse<Page<QuestionSubmitVO>> listQuestionSubmitByPage(@RequestBody QuestionSubmitQueryRequest questionSubmitQueryRequest,
                                                                          HttpServletRequest request) {
        long current = questionSubmitQueryRequest.getCurrent();
        long size = questionSubmitQueryRequest.getPageSize();
        // 从数据库中查询原始的题目提交分页信息
        Page<QuestionSubmit> questionSubmitPage = questionSubmitService.page(new Page<>(current, size),
                questionSubmitService.getQueryWrapper(questionSubmitQueryRequest));
        final User loginUser = userService.getLoginUser(request);
        // 返回脱敏信息
        return ResultUtils.success(questionSubmitService.getQuestionSubmitVOPage(questionSubmitPage, loginUser));
    }

    /**
     * 根据 id 获取提交（封装类）
     *
     * 说明：判题是异步流程，前端如需实时展示结果，应在提交后按 id 轮询该接口直到 status != RUNNING。
     */
    @GetMapping("/get/vo")
    public BaseResponse<QuestionSubmitVO> getQuestionSubmitVOById(long id, HttpServletRequest request) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        QuestionSubmit questionSubmit = questionSubmitService.getById(id);
        if (questionSubmit == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        return ResultUtils.success(questionSubmitService.getQuestionSubmitVO(questionSubmit, loginUser));
    }

}
