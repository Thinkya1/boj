package biny.biny.mq;

import biny.biny.common.ErrorCode;
import biny.biny.constant.MqConstant;
import biny.biny.exception.BusinessException;
import biny.biny.judge.JudgeService;
import biny.biny.judge.codesandbox.model.JudgeInfo;
import biny.biny.model.entity.QuestionSubmit;
import biny.biny.model.enums.QuestionSubmitStatusEnum;
import biny.biny.service.QuestionSubmitService;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Date;

@Component
@Slf4j
public class JudgeMessageConsumer {

    /**
     * 兼容历史异常导致的状态卡死：RUNNING 超过该时间，判定为系统错误。
     */
    private static final long STALE_RUNNING_MS = 10 * 60 * 1000L;

    private final JudgeService judgeService;

    private final QuestionSubmitService questionSubmitService;

    public JudgeMessageConsumer(@Lazy JudgeService judgeService,
                                QuestionSubmitService questionSubmitService) {
        this.judgeService = judgeService;
        this.questionSubmitService = questionSubmitService;
    }

    @RabbitListener(queues = MqConstant.JUDGE_QUEUE, containerFactory = "judgeRabbitListenerContainerFactory")
    public void onMessage(String message, Message amqpMessage, Channel channel) {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        try {
            if (StringUtils.isBlank(message)) {
                log.warn("判题消息为空，已跳过");
                return;
            }
            long questionSubmitId;
            try {
                questionSubmitId = Long.parseLong(message.trim());
            } catch (NumberFormatException e) {
                log.error("判题消息格式错误，message={}", message);
                return;
            }
            try {
                judgeService.doJudge(questionSubmitId);
            } catch (BusinessException e) {
                // 业务幂等：重复消息 / 状态已变化，直接 ACK 不重试
                if (e.getCode() == ErrorCode.OPERATION_ERROR.getCode()) {
                    // 兼容历史异常导致状态卡在 RUNNING：如果长时间未更新，落库系统错误避免前端一直“判题中”
                    try {
                        QuestionSubmit current = questionSubmitService.getById(questionSubmitId);
                        if (current != null && QuestionSubmitStatusEnum.RUNNING.getValue().equals(current.getStatus())) {
                            Date updateTime = current.getUpdateTime();
                            long now = System.currentTimeMillis();
                            boolean stale = updateTime == null || now - updateTime.getTime() > STALE_RUNNING_MS;
                            if (stale) {
                                markSystemError(questionSubmitId, "stale running");
                                log.warn("判题提交长时间处于 RUNNING，已判定为系统错误并落库。submitId={}, updateTime={}",
                                        questionSubmitId, updateTime);
                            }
                        }
                    } catch (Exception ignored) {
                        // ignore
                    }
                    log.info("判题消息已处理或处理中，已跳过。submitId={}, code={}, msg={}",
                            questionSubmitId, e.getCode(), e.getMessage());
                    return;
                }
                markSystemError(questionSubmitId, e.getMessage());
                log.warn("判题失败，已落库为系统错误并 ACK。submitId={}, code={}, msg={}",
                        questionSubmitId, e.getCode(), e.getMessage());
            } catch (Exception e) {
                markSystemError(questionSubmitId, e.getMessage());
                log.error("判题异常，已落库为系统错误并 ACK。submitId={}, msg={}", questionSubmitId, e.getMessage(), e);
            }
        } finally {
            // 手动 ACK：避免异常导致消息重试死循环刷屏
            try {
                channel.basicAck(deliveryTag, false);
            } catch (IOException e) {
                log.error("判题消息 ACK 失败，deliveryTag={}", deliveryTag, e);
            }
        }
    }

    private void markSystemError(long questionSubmitId, String errorMessage) {
        try {
            QuestionSubmit current = questionSubmitService.getById(questionSubmitId);
            if (current == null) {
                return;
            }
            Integer status = current.getStatus();
            if (!QuestionSubmitStatusEnum.WAITING.getValue().equals(status)
                    && !QuestionSubmitStatusEnum.RUNNING.getValue().equals(status)) {
                return;
            }

            JudgeInfo errorInfo = new JudgeInfo();
            // 避免把内部异常细节暴露给前端，这里统一落库为“系统错误”
            errorInfo.setMessage("System Error");

            QuestionSubmit failUpdate = new QuestionSubmit();
            failUpdate.setStatus(QuestionSubmitStatusEnum.FAILED.getValue());
            failUpdate.setResult("SE");
            failUpdate.setJudgeInfo(JSONUtil.toJsonStr(errorInfo));

            LambdaUpdateWrapper<QuestionSubmit> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(QuestionSubmit::getId, questionSubmitId)
                    .in(QuestionSubmit::getStatus,
                            QuestionSubmitStatusEnum.WAITING.getValue(),
                            QuestionSubmitStatusEnum.RUNNING.getValue())
                    .eq(QuestionSubmit::getIsDelete, 0);
            questionSubmitService.update(failUpdate, wrapper);
        } catch (Exception e) {
            log.error("判题失败落库异常，submitId={}, msg={}", questionSubmitId, errorMessage, e);
        }
    }
}
