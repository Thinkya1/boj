package biny.biny.model.vo;

import java.io.Serializable;
import lombok.Data;

/**
 * Token 刷新结果
 */
@Data
public class TokenRefreshVO implements Serializable {

    private String accessToken;

    private Long expiresInSeconds;

    private static final long serialVersionUID = 1L;
}

