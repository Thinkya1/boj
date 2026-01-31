package biny.biny.judge;

import biny.biny.judge.codesandbox.model.JudgeInfo;
import biny.biny.judge.strategy.DefaultJudgeStrategy;
import biny.biny.judge.strategy.JudgeContext;
import biny.biny.judge.strategy.JudgeStrategy;
import biny.biny.model.enums.QuestionSubmitLanguageEnum;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 判题管理 策略
 */
@Service
public class JudgeManager {

    private final Map<String, JudgeStrategy> strategyMap;

    private final JudgeStrategy defaultStrategy;

    public JudgeManager() {
        JudgeStrategy defaultStrategy = new DefaultJudgeStrategy();
        Map<String, JudgeStrategy> map = new HashMap<>();
        for (QuestionSubmitLanguageEnum languageEnum : QuestionSubmitLanguageEnum.values()) {
            map.put(languageEnum.getValue(), defaultStrategy);
        }
        this.strategyMap = Collections.unmodifiableMap(map);
        this.defaultStrategy = defaultStrategy;
    }

    /**
     * 执行判题
     *
     * @param judgeContext
     * @return
     */
    JudgeInfo doJudge(JudgeContext judgeContext) {
        String language = null;
        if (judgeContext != null && judgeContext.getQuestionSubmit() != null) {
            language = judgeContext.getQuestionSubmit().getLanguage();
        }
        QuestionSubmitLanguageEnum languageEnum = QuestionSubmitLanguageEnum.getEnumByValue(language);
        String strategyKey = languageEnum == null ? null : languageEnum.getValue();
        JudgeStrategy judgeStrategy = strategyMap.getOrDefault(strategyKey, defaultStrategy);
        return judgeStrategy.doJudge(judgeContext);
    }

}
