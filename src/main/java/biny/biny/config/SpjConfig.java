package biny.biny.config;

import lombok.Data;

/**
 * SPJ 配置（从 judgeConfig.spj 解析）
 */
@Data
public class SpjConfig {

    /**
     * 是否启用 SPJ
     */
    private Boolean enabled;

    /**
     * 是否忽略顺序
     */
    private Boolean ignoreOrder;

    /**
     * 比较单位：LINE / TOKEN
     */
    private String compareUnit;

    /**
     * 浮点误差容忍
     */
    private Double floatEps;
}
