package biny.biny.model.dto.question;

import biny.biny.config.SpjConfig;
import lombok.Data;

/**
 * 题目配置
 */
@Data
public class JudgeConfig {

    /**
     * 时间限制（ms）
     */
    private Long timeLimit;

    /**
     * 内存限制（KB）
     */
    private Long memoryLimit;

    /**
     * 堆栈限制（KB）
     */
    private Long stackLimit;

    /**
     * SPJ 配置（可选）
     */
    private SpjConfig spj;
}
