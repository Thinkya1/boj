package biny.biny.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import biny.biny.common.ErrorCode;
import biny.biny.constant.CommonConstant;
import biny.biny.constant.MqConstant;
import biny.biny.exception.BusinessException;
import biny.biny.judge.codesandbox.model.JudgeInfo;
import biny.biny.mapper.QuestionSubmitMapper;
import biny.biny.model.dto.questionsubmit.QuestionSubmitAddRequest;
import biny.biny.model.dto.questionsubmit.QuestionSubmitQueryRequest;
import biny.biny.model.entity.Question;
import biny.biny.model.entity.QuestionSubmit;
import biny.biny.model.entity.User;
import biny.biny.model.enums.QuestionSubmitLanguageEnum;
import biny.biny.model.enums.QuestionSubmitStatusEnum;
import biny.biny.model.vo.QuestionVO;
import biny.biny.model.vo.QuestionSubmitVO;
import biny.biny.service.QuestionService;
import biny.biny.service.QuestionSubmitService;
import biny.biny.service.UserService;
import biny.biny.utils.SqlUtils;
import cn.hutool.json.JSONUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
* @author biny
* @description 针对表【question_submit(题目提交)】的数据库操作Service实现
* @createDate 2023-08-07 20:58:53
*/
@Service
@Slf4j
public class QuestionSubmitServiceImpl extends ServiceImpl<QuestionSubmitMapper, QuestionSubmit>
    implements QuestionSubmitService{


    private final QuestionService questionService;

    private final UserService userService;

    private final RabbitTemplate rabbitTemplate;

    public QuestionSubmitServiceImpl(QuestionService questionService,
                                     UserService userService,
                                     RabbitTemplate rabbitTemplate) {
        this.questionService = questionService;
        this.userService = userService;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 提交题目
     *
     * @param questionSubmitAddRequest
     * @param loginUser
     * @return
     */
    @Override
    public long doQuestionSubmit(QuestionSubmitAddRequest questionSubmitAddRequest, User loginUser) {
        // 校验编程语言是否合法
        String language = questionSubmitAddRequest.getLanguage();
        QuestionSubmitLanguageEnum languageEnum = QuestionSubmitLanguageEnum.getEnumByValue(language);
        if (languageEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "编程语言错误");
        }
        language = languageEnum.getValue();
        long questionId = questionSubmitAddRequest.getQuestionId();
        // 判断实体是否存在，根据类别获取实体
        Question question = questionService.getById(questionId);
        if (question == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        // 是否已提交题目
        long userId = loginUser.getId();
        // 每个用户串行提交题目
        QuestionSubmit questionSubmit = new QuestionSubmit();
        questionSubmit.setUserId(userId);
        questionSubmit.setQuestionId(questionId);
        questionSubmit.setCode(questionSubmitAddRequest.getCode());
        questionSubmit.setLanguage(language);
        // 设置初始状态
        questionSubmit.setStatus(QuestionSubmitStatusEnum.WAITING.getValue());
        questionSubmit.setJudgeInfo("{}");
        boolean save = this.save(questionSubmit);
        if (!save){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "数据插入失败");
        }
        boolean updateCount = questionService.update()
                .eq("id", questionId)
                .setSql("submitNum = IFNULL(submitNum, 0) + 1")
                .update();
        if (!updateCount) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "更新提交计数失败");
        }
        Long questionSubmitId = questionSubmit.getId();
        // 执行判题服务
        try {
            rabbitTemplate.convertAndSend(MqConstant.JUDGE_QUEUE, String.valueOf(questionSubmitId));
        } catch (Exception e) {
            log.error("判题消息发送异常，submitId={}", questionSubmitId, e);
            // 避免消息未入队导致记录一直处于 WAITING（前端一直“判题中/等待判题”）
            JudgeInfo errorInfo = new JudgeInfo();
            errorInfo.setMessage("System Error");
            QuestionSubmit failUpdate = new QuestionSubmit();
            failUpdate.setStatus(QuestionSubmitStatusEnum.FAILED.getValue());
            failUpdate.setResult("SE");
            failUpdate.setJudgeInfo(JSONUtil.toJsonStr(errorInfo));
            LambdaUpdateWrapper<QuestionSubmit> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(QuestionSubmit::getId, questionSubmitId)
                    .eq(QuestionSubmit::getStatus, QuestionSubmitStatusEnum.WAITING.getValue())
                    .eq(QuestionSubmit::getIsDelete, 0);
            this.update(failUpdate, wrapper);
        }
        return questionSubmitId;
    }


    /**
     * 获取查询包装类（用户根据哪些字段查询，根据前端传来的请求对象，得到 mybatis 框架支持的查询 QueryWrapper 类）
     *
     * @param questionSubmitQueryRequest
     * @return
     */
    @Override
    public QueryWrapper<QuestionSubmit> getQueryWrapper(QuestionSubmitQueryRequest questionSubmitQueryRequest) {
        QueryWrapper<QuestionSubmit> queryWrapper = new QueryWrapper<>();
        if (questionSubmitQueryRequest == null) {
            return queryWrapper;
        }
        String language = questionSubmitQueryRequest.getLanguage();
        if (StringUtils.isNotBlank(language)) {
            QuestionSubmitLanguageEnum languageEnum = QuestionSubmitLanguageEnum.getEnumByValue(language);
            if (languageEnum != null) {
                language = languageEnum.getValue();
            }
        }
        Integer status = questionSubmitQueryRequest.getStatus();
        Long questionId = questionSubmitQueryRequest.getQuestionId();
        Integer questionNumber = questionSubmitQueryRequest.getQuestionNumber();
        Long userId = questionSubmitQueryRequest.getUserId();
        String sortField = questionSubmitQueryRequest.getSortField();
        String sortOrder = questionSubmitQueryRequest.getSortOrder();

        // 拼接查询条件
        if (ObjectUtils.isNotEmpty(questionNumber)) {
            Question question = questionService.lambdaQuery()
                    .select(Question::getId)
                    .eq(Question::getQuestionNumber, questionNumber)
                    .one();
            if (question == null) {
                queryWrapper.eq("questionId", -1);
                return queryWrapper;
            }
            questionId = question.getId();
        }
        queryWrapper.eq(StringUtils.isNotBlank(language), "language", language);
        queryWrapper.eq(ObjectUtils.isNotEmpty(userId), "userId", userId);
        queryWrapper.eq(ObjectUtils.isNotEmpty(questionId), "questionId", questionId);
        queryWrapper.eq(QuestionSubmitStatusEnum.getEnumByValue(status) != null, "status", status);
        queryWrapper.eq("isDelete", false);
        queryWrapper.orderBy(SqlUtils.validSortField(sortField), sortOrder.equals(CommonConstant.SORT_ORDER_ASC),
                sortField);
        return queryWrapper;
    }

    @Override
    public QuestionSubmitVO getQuestionSubmitVO(QuestionSubmit questionSubmit, User loginUser) {
        QuestionSubmitVO questionSubmitVO = QuestionSubmitVO.objToVo(questionSubmit);
        // 脱敏：仅本人和管理员能看见自己（提交 userId 和登录用户 id 不同）提交的代码
        long userId = loginUser.getId();
        // 处理脱敏
        if (userId != questionSubmit.getUserId() && !userService.isAdmin(loginUser)) {
            questionSubmitVO.setCode(null);
        }
        return questionSubmitVO;
    }

    @Override
    public Page<QuestionSubmitVO> getQuestionSubmitVOPage(Page<QuestionSubmit> questionSubmitPage, User loginUser) {
        List<QuestionSubmit> questionSubmitList = questionSubmitPage.getRecords();
        Page<QuestionSubmitVO> questionSubmitVOPage = new Page<>(questionSubmitPage.getCurrent(), questionSubmitPage.getSize(), questionSubmitPage.getTotal());
        if (CollectionUtils.isEmpty(questionSubmitList)) {
            return questionSubmitVOPage;
        }
        Set<Long> userIdSet = questionSubmitList.stream()
                .map(QuestionSubmit::getUserId)
                .collect(Collectors.toSet());
        Map<Long, User> userIdMap = userService.listByIds(userIdSet).stream()
                .collect(Collectors.toMap(User::getId, item -> item, (a, b) -> a));
        Set<Long> questionIdSet = questionSubmitList.stream()
                .map(QuestionSubmit::getQuestionId)
                .collect(Collectors.toSet());
        Map<Long, Question> questionIdMap = questionService.listByIds(questionIdSet).stream()
                .collect(Collectors.toMap(Question::getId, item -> item, (a, b) -> a));
        List<QuestionSubmitVO> questionSubmitVOList = questionSubmitList.stream()
                .map(questionSubmit -> {
                    QuestionSubmitVO questionSubmitVO = getQuestionSubmitVO(questionSubmit, loginUser);
                    User user = userIdMap.get(questionSubmit.getUserId());
                    questionSubmitVO.setUserVO(userService.getUserVO(user));
                    Question question = questionIdMap.get(questionSubmit.getQuestionId());
                    if (question != null) {
                        QuestionVO questionVO = new QuestionVO();
                        questionVO.setId(question.getId());
                        questionVO.setQuestionNumber(question.getQuestionNumber());
                        questionVO.setTitle(question.getTitle());
                        questionVO.setSubmitNum(question.getSubmitNum());
                        questionVO.setAcceptedNum(question.getAcceptedNum());
                        questionSubmitVO.setQuestionVO(questionVO);
                    }
                    return questionSubmitVO;
                })
                .collect(Collectors.toList());
        questionSubmitVOPage.setRecords(questionSubmitVOList);
        return questionSubmitVOPage;
    }


}




