package biny.biny.judge.codesandbox.impl;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONUtil;
import cn.hutool.http.HttpRequest;
import com.alibaba.excel.util.StringUtils;
import biny.biny.common.ErrorCode;
import biny.biny.exception.BusinessException;
import biny.biny.judge.codesandbox.CodeSandbox;
import biny.biny.judge.codesandbox.model.ExecuteCodeRequest;
import biny.biny.judge.codesandbox.model.ExecuteCodeResponse;

/**
 * 远程代码沙箱（实际调用接口的沙箱）
 */
public class RemoteCodeSandbox implements CodeSandbox {

    private static final String DEFAULT_REMOTE_URL = "http://localhost:8099/executeCode";

    private static final String DEFAULT_AUTH_HEADER = "auth";

    private static String envOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return StringUtils.isBlank(value) ? defaultValue : value;
    }


    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        System.out.println("远程代码沙箱");
        String url = envOrDefault("CODESANDBOX_REMOTE_URL", DEFAULT_REMOTE_URL);
        String authHeader = envOrDefault("CODESANDBOX_REMOTE_AUTH_HEADER", DEFAULT_AUTH_HEADER);
        String authSecret = envOrDefault("CODESANDBOX_REMOTE_AUTH_SECRET", "");
        String json = JSONUtil.toJsonStr(executeCodeRequest);
        HttpRequest request = HttpUtil.createPost(url);
        if (!StringUtils.isBlank(authSecret)) {
            request.header(authHeader, authSecret);
        }
        String responseStr = request.body(json)
                .execute()
                .body();
        if (StringUtils.isBlank(responseStr)) {
            throw new BusinessException(ErrorCode.API_REQUEST_ERROR, "executeCode remoteSandbox error, message = " + responseStr);
        }
        return JSONUtil.toBean(responseStr, ExecuteCodeResponse.class);
    }
}
