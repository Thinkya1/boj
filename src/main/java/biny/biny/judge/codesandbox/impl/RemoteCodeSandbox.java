package biny.biny.judge.codesandbox.impl;

import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONUtil;
import cn.hutool.http.HttpRequest;
import biny.biny.common.ErrorCode;
import biny.biny.exception.BusinessException;
import biny.biny.judge.codesandbox.CodeSandbox;
import biny.biny.judge.codesandbox.model.ExecuteCodeRequest;
import biny.biny.judge.codesandbox.model.ExecuteCodeResponse;
import biny.biny.judge.codesandbox.model.JudgeInfo;
import java.util.Collections;
import org.apache.commons.lang3.StringUtils;

/**
 * 远程代码沙箱（实际调用接口的沙箱）
 */
public class RemoteCodeSandbox implements CodeSandbox {

    private static final String DEFAULT_REMOTE_URL = "http://localhost:8099/executeCode";

    private static final String DEFAULT_AUTH_HEADER = "auth";

    private static final String DEFAULT_AUTH_SECRET = "secretKey";

    private static String envOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return StringUtils.isBlank(value) ? defaultValue : value;
    }


    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        String url = envOrDefault("CODESANDBOX_REMOTE_URL", DEFAULT_REMOTE_URL);
        String authHeader = envOrDefault("CODESANDBOX_REMOTE_AUTH_HEADER", DEFAULT_AUTH_HEADER);
        String authSecret = envOrDefault("CODESANDBOX_REMOTE_AUTH_SECRET", DEFAULT_AUTH_SECRET);
        String json = JSONUtil.toJsonStr(executeCodeRequest);
        HttpRequest request = HttpUtil.createPost(url)
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Accept", "application/json");
        if (!StringUtils.isBlank(authSecret)) {
            request.header(authHeader, authSecret);
        }
        try (HttpResponse response = request.body(json).execute()) {
            int httpStatus = response.getStatus();
            String responseStr = response.body();
            if (httpStatus != 200) {
                if (httpStatus == 403) {
                    String hint = "（鉴权失败，请检查 CODESANDBOX_REMOTE_AUTH_SECRET / CODESANDBOX_REMOTE_AUTH_HEADER）";
                    throw new BusinessException(ErrorCode.API_REQUEST_ERROR,
                            "executeCode remoteSandbox httpStatus=" + httpStatus + hint + ", body=" + responseStr);
                }
                // 兼容沙箱异常时返回的 Spring Boot 默认错误 JSON（包含 message/trace 等字段）
                String errorMessage = responseStr;
                if (StringUtils.isNotBlank(responseStr)) {
                    String trimmed = responseStr.trim();
                    if (trimmed.startsWith("{")) {
                        try {
                            String msg = JSONUtil.parseObj(trimmed).getStr("message");
                            if (StringUtils.isNotBlank(msg)) {
                                errorMessage = msg;
                            }
                        } catch (Exception ignored) {
                            // ignore parse errors and fallback to raw body
                        }
                    }
                }
                ExecuteCodeResponse errorResp = new ExecuteCodeResponse();
                errorResp.setStatus(2);
                errorResp.setMessage(errorMessage);
                errorResp.setOutputList(Collections.emptyList());
                errorResp.setJudgeInfo(new JudgeInfo());
                return errorResp;
            }
            if (StringUtils.isBlank(responseStr)) {
                throw new BusinessException(ErrorCode.API_REQUEST_ERROR,
                        "executeCode remoteSandbox empty body, please check remote service. url=" + url);
            }
            String trimmed = responseStr.trim();
            if (!trimmed.startsWith("{")) {
                throw new BusinessException(ErrorCode.API_REQUEST_ERROR,
                        "executeCode remoteSandbox invalid json, body=" + responseStr);
            }
            return JSONUtil.toBean(trimmed, ExecuteCodeResponse.class);
        }
    }
}
