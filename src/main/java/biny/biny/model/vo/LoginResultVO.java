package biny.biny.model.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.io.Serializable;
import lombok.Data;

/**
 * 登录结果（JWT）
 */
@Data
public class LoginResultVO implements Serializable {

    private LoginUserVO user;

    private String accessToken;

    private Long expiresInSeconds;

    /**
     * Refresh Token 仅用于服务端写入 HttpOnly Cookie，不返回给前端
     */
    @JsonIgnore
    private String refreshToken;

    private static final long serialVersionUID = 1L;
}

