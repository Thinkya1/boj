package biny.biny.model.vo;

import cn.hutool.json.JSONUtil;
import biny.biny.model.dto.question.JudgeConfig;
import biny.biny.model.dto.question.JudgeCase;
import biny.biny.model.entity.Question;
import java.io.Serializable;
import java.util.Date;
import java.util.List;
import lombok.Data;
import org.springframework.beans.BeanUtils;
import org.apache.commons.lang3.StringUtils;


@Data
public class QuestionVO implements Serializable {
    private Long id;

    private Integer questionNumber;

    private Integer difficulty;

    private String title;

    private String content;

    private String answer;

    private List<String> tags;

    private Integer submitNum;

    private Integer acceptedNum;

    private JudgeConfig judgeConfig;

    private List<JudgeCase> sampleCase;

    private Integer thumbNum;

    private Integer favourNum;

    private Long userId;

    private Date createTime;

    private Date updateTime;

    private UserVO userVO;

    public static Question voToObj(QuestionVO questionVO) {
        if (questionVO == null) {
            return null;
        }
        Question question = new Question();
        BeanUtils.copyProperties(questionVO, question);
        List<String> tagList = questionVO.getTags();
        if (tagList != null) {
            question.setTags(JSONUtil.toJsonStr(tagList));
        }
        JudgeConfig voJudgeConfig = questionVO.getJudgeConfig();
        if (voJudgeConfig != null) {
            question.setJudgeConfig(JSONUtil.toJsonStr(voJudgeConfig));
        }
        List<JudgeCase> sampleCaseList = questionVO.getSampleCase();
        if (sampleCaseList != null) {
            question.setSampleCase(JSONUtil.toJsonStr(sampleCaseList));
        }
        return question;
    }

    public static QuestionVO objToVo(Question question) {
        if (question == null) {
            return null;
        }
        QuestionVO questionVO = new QuestionVO();
        BeanUtils.copyProperties(question, questionVO);
        List<String> tagList = JSONUtil.toList(question.getTags(), String.class);
        questionVO.setTags(tagList);
        String judgeConfigStr = question.getJudgeConfig();
        questionVO.setJudgeConfig(JSONUtil.toBean(judgeConfigStr, JudgeConfig.class));
        String sampleCaseStr = question.getSampleCase();
        if (StringUtils.isNotBlank(sampleCaseStr)) {
            questionVO.setSampleCase(JSONUtil.toList(sampleCaseStr, JudgeCase.class));
        }
        return questionVO;
    }

    private static final long serialVersionUID = 1L;
}
