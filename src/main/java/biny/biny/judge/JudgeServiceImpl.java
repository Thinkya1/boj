package biny.biny.judge;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import biny.biny.common.ErrorCode;
import biny.biny.exception.BusinessException;
import biny.biny.judge.codesandbox.CodeSandbox;
import biny.biny.judge.codesandbox.CodeSandboxFactory;
import biny.biny.judge.codesandbox.CodeSandboxProxy;
import biny.biny.judge.codesandbox.model.ExecuteCodeRequest;
import biny.biny.judge.codesandbox.model.ExecuteCodeResponse;
import biny.biny.judge.codesandbox.model.JudgeCaseResult;
import biny.biny.judge.codesandbox.model.JudgeInfo;
import biny.biny.judge.codesandbox.model.SpjCheckResult;
import biny.biny.judge.spj.SpjCheckerService;
import biny.biny.config.SpjConfig;
import biny.biny.judge.strategy.JudgeContext;
import biny.biny.model.dto.question.JudgeConfig;
import biny.biny.model.dto.question.JudgeCase;
import biny.biny.model.dto.questionsubmit.QuestionSubmitAddRequest;
import biny.biny.model.entity.Question;
import biny.biny.model.entity.QuestionSubmit;
import biny.biny.model.enums.JudgeInfoMessageEnum;
import biny.biny.model.enums.QuestionSubmitLanguageEnum;
import biny.biny.model.enums.QuestionSubmitStatusEnum;
import biny.biny.service.QuestionService;
import biny.biny.service.QuestionSubmitService;
import biny.biny.utils.MemoryUnitUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class JudgeServiceImpl implements JudgeService {

    private final QuestionSubmitService questionSubmitService;

    private final QuestionService questionService;

    @Value("${codesandbox.type:example}")
    private String type;

    private final JudgeManager judgeManager;

    private final SpjCheckerService spjCheckerService;

    public JudgeServiceImpl(QuestionSubmitService questionSubmitService,
                            QuestionService questionService,
                            JudgeManager judgeManager,
                            SpjCheckerService spjCheckerService) {
        this.questionSubmitService = questionSubmitService;
        this.questionService = questionService;
        this.judgeManager = judgeManager;
        this.spjCheckerService = spjCheckerService;
    }

    @Override
    public QuestionSubmit doJudge(long questionSubmitId) {
        // 1) 获取题目提交与题目信息
        QuestionSubmit questionSubmit = questionSubmitService.getById(questionSubmitId);
        if (questionSubmit == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "提交信息不存在");
        }
        Long questionId = questionSubmit.getQuestionId();
        Question question = questionService.getById(questionId);
        if (question == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "题目不存在");
        }
        // 2) 非等待状态拒绝重复判题
        if (!QuestionSubmitStatusEnum.WAITING.getValue().equals(questionSubmit.getStatus())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "题目正在判题中");
        }

        // 3) 更新状态为“判题中”（幂等）
        QuestionSubmit runningUpdate = new QuestionSubmit();
        runningUpdate.setStatus(QuestionSubmitStatusEnum.RUNNING.getValue());
        LambdaUpdateWrapper<QuestionSubmit> runningWrapper = new LambdaUpdateWrapper<>();
        runningWrapper.eq(QuestionSubmit::getId, questionSubmitId)
                .eq(QuestionSubmit::getStatus, QuestionSubmitStatusEnum.WAITING.getValue())
                .eq(QuestionSubmit::getIsDelete, 0);
        boolean update = questionSubmitService.update(runningUpdate, runningWrapper);
        if (!update) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "提交状态已变化，无法判题");
        }

        try {
            // 4) 调用沙箱执行
            CodeSandbox codeSandbox = CodeSandboxFactory.newInstance(type);
            codeSandbox = new CodeSandboxProxy(codeSandbox);
            String language = questionSubmit.getLanguage();
            String code = questionSubmit.getCode();
            String judgeCaseStr = question.getJudgeCase();
            List<JudgeCase> judgeCaseList = JSONUtil.toList(judgeCaseStr, JudgeCase.class);
            List<String> inputList = judgeCaseList.stream().map(JudgeCase::getInput).collect(Collectors.toList());
            ExecuteCodeRequest executeCodeRequest = ExecuteCodeRequest.builder()
                    .code(code)
                    .language(language)
                    .inputList(inputList)
                    .build();
            ExecuteCodeResponse executeCodeResponse = codeSandbox.executeCode(executeCodeRequest);
            if (executeCodeResponse == null || executeCodeResponse.getJudgeInfo() == null) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "沙箱响应异常");
            }
            normalizeMemoryUnit(executeCodeResponse);

            // 4.1) 沙箱未正常执行（编译错误 / 运行错误 / 系统错误）直接落库失败，不走判题策略
            Integer executeStatus = executeCodeResponse.getStatus();
            if (!Integer.valueOf(1).equals(executeStatus)) {
                JudgeInfo errorInfo = executeCodeResponse.getJudgeInfo();
                if (errorInfo == null) {
                    errorInfo = new JudgeInfo();
                    executeCodeResponse.setJudgeInfo(errorInfo);
                }
                String sandboxMessage = executeCodeResponse.getMessage();
                String judgeMessage = "System Error";
                if (Integer.valueOf(3).equals(executeStatus)) {
                    judgeMessage = "Runtime Error";
                } else if (Integer.valueOf(2).equals(executeStatus)) {
                    String lower = sandboxMessage == null ? "" : sandboxMessage.toLowerCase();
                    boolean looksLikeCompileError = lower.contains("error:")
                            && (lower.contains(".java") || lower.contains(".c") || lower.contains(".cpp"));
                    if (looksLikeCompileError) {
                        judgeMessage = "Compile Error";
                    }
                }
                errorInfo.setMessage(judgeMessage);

                JudgeInfoMessageEnum messageEnum = getJudgeInfoMessageEnum(judgeMessage);
                String result = getResultByMessageEnum(messageEnum);
                QuestionSubmit finishUpdate = new QuestionSubmit();
                finishUpdate.setStatus(QuestionSubmitStatusEnum.FAILED.getValue());
                finishUpdate.setResult(result);
                finishUpdate.setJudgeInfo(JSONUtil.toJsonStr(errorInfo));
                LambdaUpdateWrapper<QuestionSubmit> finishWrapper = new LambdaUpdateWrapper<>();
                finishWrapper.eq(QuestionSubmit::getId, questionSubmitId)
                        .eq(QuestionSubmit::getStatus, QuestionSubmitStatusEnum.RUNNING.getValue())
                        .eq(QuestionSubmit::getIsDelete, 0);
                update = questionSubmitService.update(finishUpdate, finishWrapper);
                if (!update) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR, "判题结果更新失败");
                }
                return questionSubmitService.getById(questionSubmitId);
            }

            // 5) 判题策略生成结果
            JudgeInfo judgeInfo = buildJudgeInfo(executeCodeResponse, question, questionSubmit, inputList, judgeCaseList);

            // 6) 落库判题结果（幂等）
            String message = judgeInfo.getMessage();
            JudgeInfoMessageEnum messageEnum = getJudgeInfoMessageEnum(message);
            String result = getResultByMessageEnum(messageEnum);
            boolean accepted = JudgeInfoMessageEnum.ACCEPTED.equals(messageEnum);
            QuestionSubmit finishUpdate = new QuestionSubmit();
            finishUpdate.setStatus(accepted ? QuestionSubmitStatusEnum.SUCCEED.getValue()
                    : QuestionSubmitStatusEnum.FAILED.getValue());
            finishUpdate.setResult(result);
            finishUpdate.setJudgeInfo(JSONUtil.toJsonStr(judgeInfo));
            LambdaUpdateWrapper<QuestionSubmit> finishWrapper = new LambdaUpdateWrapper<>();
            finishWrapper.eq(QuestionSubmit::getId, questionSubmitId)
                    .eq(QuestionSubmit::getStatus, QuestionSubmitStatusEnum.RUNNING.getValue())
                    .eq(QuestionSubmit::getIsDelete, 0);
            update = questionSubmitService.update(finishUpdate, finishWrapper);
            if (!update) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "判题结果更新失败");
            }

            if (accepted) {
                boolean acceptedUpdate = questionService.update()
                        .eq("id", questionId)
                        .setSql("acceptedNum = IFNULL(acceptedNum, 0) + 1")
                        .update();
                if (!acceptedUpdate) {
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR, "通过数更新失败");
                }
            }
            return questionSubmitService.getById(questionSubmitId);
        } catch (Exception e) {
            log.error("判题执行异常，提交id={}", questionSubmitId, e);
            JudgeInfo errorInfo = new JudgeInfo();
            errorInfo.setMessage("System Error");
            QuestionSubmit failUpdate = new QuestionSubmit();
            failUpdate.setStatus(QuestionSubmitStatusEnum.FAILED.getValue());
            failUpdate.setResult("SE");//System Error
            failUpdate.setJudgeInfo(JSONUtil.toJsonStr(errorInfo));
            LambdaUpdateWrapper<QuestionSubmit> failWrapper = new LambdaUpdateWrapper<>();
            failWrapper.eq(QuestionSubmit::getId, questionSubmitId)
                    .eq(QuestionSubmit::getStatus, QuestionSubmitStatusEnum.RUNNING.getValue())
                    .eq(QuestionSubmit::getIsDelete, 0);
            questionSubmitService.update(failUpdate, failWrapper);
            return questionSubmitService.getById(questionSubmitId);
        }
    }

    @Override
    public ExecuteCodeResponse runQuestion(QuestionSubmitAddRequest questionSubmitAddRequest) {
        if (questionSubmitAddRequest == null || questionSubmitAddRequest.getQuestionId() == null
                || questionSubmitAddRequest.getQuestionId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        String language = questionSubmitAddRequest.getLanguage();
        QuestionSubmitLanguageEnum languageEnum = QuestionSubmitLanguageEnum.getEnumByValue(language);
        if (languageEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "编程语言错误");
        }
        String code = questionSubmitAddRequest.getCode();
        if (StringUtils.isBlank(code)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "代码为空");
        }
        Question question = questionService.getById(questionSubmitAddRequest.getQuestionId());
        if (question == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "题目不存在");
        }
        String sampleCaseStr = question.getSampleCase();
        List<JudgeCase> sampleCaseList = StringUtils.isBlank(sampleCaseStr)
                ? Collections.emptyList()
                : JSONUtil.toList(sampleCaseStr, JudgeCase.class);
        if (sampleCaseList == null) {
            sampleCaseList = Collections.emptyList();
        }
        if (CollectionUtils.isEmpty(sampleCaseList)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "示例用例为空");
        }
        List<String> inputList = sampleCaseList.stream().map(JudgeCase::getInput).collect(Collectors.toList());
        CodeSandbox codeSandbox = CodeSandboxFactory.newInstance(type);
        codeSandbox = new CodeSandboxProxy(codeSandbox);
        ExecuteCodeRequest executeCodeRequest = ExecuteCodeRequest.builder()
                .code(code)
                .language(languageEnum.getValue())
                .inputList(inputList)
                .build();
        ExecuteCodeResponse executeCodeResponse = codeSandbox.executeCode(executeCodeRequest);
        if (executeCodeResponse == null || executeCodeResponse.getJudgeInfo() == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "沙箱响应异常");
        }
        normalizeMemoryUnit(executeCodeResponse);
        Integer status = executeCodeResponse.getStatus();
        if (!Integer.valueOf(1).equals(status)) {
            JudgeInfo errorInfo = executeCodeResponse.getJudgeInfo();
            if (errorInfo == null) {
                errorInfo = new JudgeInfo();
                executeCodeResponse.setJudgeInfo(errorInfo);
            }
            if (StringUtils.isBlank(errorInfo.getMessage())) {
                errorInfo.setMessage(executeCodeResponse.getMessage());
            }
            executeCodeResponse.setStatus(QuestionSubmitStatusEnum.FAILED.getValue());
            return executeCodeResponse;
        }
        QuestionSubmit questionSubmit = new QuestionSubmit();
        questionSubmit.setLanguage(languageEnum.getValue());
        JudgeInfo judgeInfo = buildJudgeInfo(executeCodeResponse, question, questionSubmit, inputList, sampleCaseList);
        executeCodeResponse.setJudgeInfo(judgeInfo);
        JudgeInfoMessageEnum messageEnum = getJudgeInfoMessageEnum(judgeInfo.getMessage());
        if (JudgeInfoMessageEnum.ACCEPTED.equals(messageEnum)) {
            executeCodeResponse.setStatus(QuestionSubmitStatusEnum.SUCCEED.getValue());
        } else {
            executeCodeResponse.setStatus(QuestionSubmitStatusEnum.FAILED.getValue());
        }
        executeCodeResponse.setMessage(judgeInfo.getMessage());
        return executeCodeResponse;
    }

    private void normalizeMemoryUnit(ExecuteCodeResponse executeCodeResponse) {
        JudgeInfo judgeInfo = executeCodeResponse.getJudgeInfo();
        if (judgeInfo == null) {
            return;
        }
        Long memory = judgeInfo.getMemory();
        if (memory == null || memory <= 0) {
            return;
        }
        if ("remote".equalsIgnoreCase(type)) {
            judgeInfo.setMemory(MemoryUnitUtil.bytesToKb(memory));
        }
    }

    private JudgeInfo buildJudgeInfo(ExecuteCodeResponse executeCodeResponse,
                                     Question question,
                                     QuestionSubmit questionSubmit,
                                     List<String> inputList,
                                     List<JudgeCase> judgeCaseList) {
        JudgeInfo baseInfo = executeCodeResponse.getJudgeInfo();
        String judgeConfigStr = question == null ? null : question.getJudgeConfig();
        boolean spjEnabled = spjCheckerService.isSpjEnabled(judgeConfigStr);
        log.info("SPJ enabled={}, questionId={}, judgeConfig={}",
                spjEnabled,
                question == null ? null : question.getId(),
                StringUtils.isBlank(judgeConfigStr) ? "<empty>" : judgeConfigStr);
        if (spjEnabled) {
            JudgeInfoMessageEnum limitEnum = checkLimit(baseInfo, judgeConfigStr);
            if (limitEnum != null) {
                return buildLimitResult(baseInfo, limitEnum, judgeCaseList == null ? 0 : judgeCaseList.size());
            }
            SpjConfig spjConfig = spjCheckerService.parseSpjConfig(judgeConfigStr);
            log.info("SPJ config: compareUnit={}, ignoreOrder={}, floatEps={}",
                    spjConfig.getCompareUnit(), spjConfig.getIgnoreOrder(), spjConfig.getFloatEps());
            SpjCheckResult spjResult = spjCheckerService.check(spjConfig, judgeCaseList,
                    executeCodeResponse.getOutputList());
            log.info("SPJ result: accepted={}, cases={}",
                    spjResult.isAccepted(),
                    spjResult.getCaseResults() == null ? 0 : spjResult.getCaseResults().size());
            JudgeInfo judgeInfo = new JudgeInfo();
            judgeInfo.setMemory(baseInfo.getMemory());
            judgeInfo.setTime(baseInfo.getTime());
            judgeInfo.setCaseResults(spjResult.getCaseResults());
            judgeInfo.setMessage(spjResult.isAccepted()
                    ? JudgeInfoMessageEnum.ACCEPTED.getValue()
                    : JudgeInfoMessageEnum.WRONG_ANSWER.getValue());
            return judgeInfo;
        }
        JudgeContext judgeContext = new JudgeContext();
        judgeContext.setJudgeInfo(baseInfo);
        judgeContext.setInputList(inputList);
        judgeContext.setOutputList(executeCodeResponse.getOutputList());
        judgeContext.setJudgeCaseList(judgeCaseList);
        judgeContext.setQuestion(question);
        judgeContext.setQuestionSubmit(questionSubmit);
        return judgeManager.doJudge(judgeContext);
    }

    private JudgeInfoMessageEnum checkLimit(JudgeInfo judgeInfo, String judgeConfigStr) {
        if (judgeInfo == null || StringUtils.isBlank(judgeConfigStr)) {
            return null;
        }
        JudgeConfig judgeConfig;
        try {
            judgeConfig = JSONUtil.toBean(judgeConfigStr, JudgeConfig.class);
        } catch (Exception e) {
            return null;
        }
        Long needMemoryLimit = judgeConfig.getMemoryLimit();
        Long needTimeLimit = judgeConfig.getTimeLimit();
        Long memory = judgeInfo.getMemory();
        Long time = judgeInfo.getTime();
        if (memory != null && needMemoryLimit != null && memory > needMemoryLimit) {
            return JudgeInfoMessageEnum.MEMORY_LIMIT_EXCEEDED;
        }
        if (time != null && needTimeLimit != null && time > needTimeLimit) {
            return JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED;
        }
        return null;
    }

    private JudgeInfo buildLimitResult(JudgeInfo baseInfo, JudgeInfoMessageEnum limitEnum, int caseCount) {
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setMemory(baseInfo.getMemory());
        judgeInfo.setTime(baseInfo.getTime());
        judgeInfo.setMessage(limitEnum.getValue());
        List<JudgeCaseResult> caseResults = new ArrayList<>();
        for (int i = 0; i < caseCount; i++) {
            JudgeCaseResult caseResult = new JudgeCaseResult();
            caseResult.setIndex(i + 1);
            caseResult.setStatus(limitEnum == JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED ? "TLE" : "MLE");
            caseResults.add(caseResult);
        }
        judgeInfo.setCaseResults(caseResults);
        return judgeInfo;
    }

    private JudgeInfoMessageEnum getJudgeInfoMessageEnum(String message) {
        if (StringUtils.isBlank(message)) {
            return JudgeInfoMessageEnum.SYSTEM_ERROR;
        }
        for (JudgeInfoMessageEnum anEnum : JudgeInfoMessageEnum.values()) {
            if (message.equals(anEnum.getValue()) || message.equals(anEnum.getText())) {
                return anEnum;
            }
        }
        return JudgeInfoMessageEnum.SYSTEM_ERROR;
    }

    private String getResultByMessageEnum(JudgeInfoMessageEnum messageEnum) {
        if (messageEnum == null) {
            return "SE";
        }
        switch (messageEnum) {
            case ACCEPTED:
                return "AC";
            case WRONG_ANSWER:
                return "WA";
            case TIME_LIMIT_EXCEEDED:
                return "TLE";
            case MEMORY_LIMIT_EXCEEDED:
                return "MLE";
            case RUNTIME_ERROR:
                return "RE";
            case COMPILE_ERROR:
                return "CE";
            case PRESENTATION_ERROR:
                return "PE";
            case OUTPUT_LIMIT_EXCEEDED:
                return "OLE";
            case DANGEROUS_OPERATION:
                return "DANGEROUS";
            case WAITING:
                return "WAITING";
            default:
                return "SE";
        }
    }
}
