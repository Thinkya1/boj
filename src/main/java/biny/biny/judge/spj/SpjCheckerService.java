package biny.biny.judge.spj;

import biny.biny.config.SpjConfig;
import biny.biny.judge.codesandbox.model.JudgeCaseResult;
import biny.biny.judge.codesandbox.model.SpjCheckResult;
import biny.biny.model.dto.question.JudgeCase;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SpjCheckerService {

    private static final String CONFIG_FILE_NAME = "spj.conf";

    private final String checkerSourcePath;

    private final String checkerBinaryPath;

    private final boolean enabled;

    private final long compileTimeoutMs;

    private final long runTimeoutMs;

    private final Object compileLock = new Object();

    public SpjCheckerService(
            @Value("${spj.enabled:false}") boolean enabled,
            @Value("${spj.checker-source:scripts/spj/checker.cpp}") String checkerSourcePath,
            @Value("${spj.checker-binary:scripts/spj/checker}") String checkerBinaryPath,
            @Value("${spj.compile-timeout-ms:10000}") long compileTimeoutMs,
            @Value("${spj.run-timeout-ms:2000}") long runTimeoutMs) {
        this.enabled = enabled;
        this.checkerSourcePath = checkerSourcePath;
        this.checkerBinaryPath = checkerBinaryPath;
        this.compileTimeoutMs = compileTimeoutMs;
        this.runTimeoutMs = runTimeoutMs;
    }

    public boolean isSpjEnabled(String judgeConfigStr) {
        if (!enabled) {
            return false;
        }
        SpjConfig config = parseSpjConfig(judgeConfigStr);
        return Boolean.TRUE.equals(config.getEnabled());
    }

    public SpjConfig parseSpjConfig(String judgeConfigStr) {
        SpjConfig config = new SpjConfig();
        config.setEnabled(false);
        if (StringUtils.isBlank(judgeConfigStr)) {
            return config;
        }
        try {
            JSONObject obj = JSONUtil.parseObj(judgeConfigStr);
            if (!obj.containsKey("spj")) {
                return config;
            }
            Object spjNode = obj.get("spj");
            if (spjNode instanceof Boolean) {
                config.setEnabled((Boolean) spjNode);
                return config;
            }
            if (spjNode instanceof JSONObject) {
                SpjConfig spjConfig = JSONUtil.toBean((JSONObject) spjNode, SpjConfig.class);
                if (spjConfig.getEnabled() == null) {
                    spjConfig.setEnabled(true);
                }
                return spjConfig;
            }
        } catch (Exception e) {
            log.warn("解析 SPJ 配置失败，judgeConfig={}", judgeConfigStr, e);
        }
        return config;
    }

    public SpjCheckResult check(SpjConfig config, List<JudgeCase> judgeCaseList, List<String> outputList) {
        SpjCheckResult result = new SpjCheckResult();
        if (config == null || !Boolean.TRUE.equals(config.getEnabled())) {
            result.setAccepted(false);
            result.setMessage("SPJ disabled");
            return result;
        }
        if (judgeCaseList == null || outputList == null || judgeCaseList.size() != outputList.size()) {
            result.setAccepted(false);
            result.setMessage("Output size mismatch");
            result.setCaseResults(buildCaseResults(judgeCaseList, false));
            return result;
        }
        try {
            Path checkerBinary = ensureCheckerBinary();
            Path tempDir = Files.createTempDirectory("spj-");
            try {
                Path configPath = writeConfigFile(tempDir, config);
                List<JudgeCaseResult> caseResults = new ArrayList<>();
                boolean accepted = true;
                for (int i = 0; i < judgeCaseList.size(); i++) {
                    JudgeCase judgeCase = judgeCaseList.get(i);
                    String input = judgeCase == null ? "" : safeString(judgeCase.getInput());
                    String answer = judgeCase == null ? "" : safeString(judgeCase.getOutput());
                    String output = safeString(outputList.get(i));
                    Path inputPath = writeCaseFile(tempDir, "input_" + i + ".txt", input);
                    Path outputPath = writeCaseFile(tempDir, "output_" + i + ".txt", output);
                    Path answerPath = writeCaseFile(tempDir, "answer_" + i + ".txt", answer);
                    int exitCode = runChecker(checkerBinary, inputPath, outputPath, answerPath, configPath);
                    boolean ok = exitCode == 0;
                    if (!ok) {
                        accepted = false;
                    }
                    JudgeCaseResult caseResult = new JudgeCaseResult();
                    caseResult.setIndex(i + 1);
                    caseResult.setStatus(ok ? "AC" : "WA");
                    caseResults.add(caseResult);
                }
                result.setAccepted(accepted);
                result.setCaseResults(caseResults);
                result.setMessage(accepted ? "Accepted" : "Wrong Answer");
            } finally {
                deleteDirectory(tempDir);
            }
        } catch (Exception e) {
            throw new IllegalStateException("SPJ checker failed", e);
        }
        return result;
    }

    private Path ensureCheckerBinary() throws IOException, InterruptedException {
        Path sourcePath = resolvePath(checkerSourcePath);
        Path binaryPath = resolveCheckerBinaryPath();
        if (Files.exists(binaryPath)) {
            if (!Files.exists(sourcePath)) {
                return binaryPath;
            }
            if (Files.getLastModifiedTime(binaryPath).toMillis()
                    >= Files.getLastModifiedTime(sourcePath).toMillis()) {
                return binaryPath;
            }
        }
        synchronized (compileLock) {
            if (Files.exists(binaryPath) && Files.exists(sourcePath)) {
                if (Files.getLastModifiedTime(binaryPath).toMillis()
                        >= Files.getLastModifiedTime(sourcePath).toMillis()) {
                    return binaryPath;
                }
            }
            compileChecker(sourcePath, binaryPath);
            return binaryPath;
        }
    }

    private void compileChecker(Path sourcePath, Path binaryPath) throws IOException, InterruptedException {
        if (!Files.exists(sourcePath)) {
            throw new IllegalStateException("checker.cpp not found: " + sourcePath);
        }
        Path workDir = sourcePath.getParent();
        String outputName = binaryPath.getFileName().toString();
        ProcessBuilder builder = new ProcessBuilder(
                "g++",
                "-std=gnu++17",
                "-O2",
                sourcePath.getFileName().toString(),
                "-o",
                outputName
        );
        builder.directory(workDir.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = readProcessOutput(process);
        boolean finished = process.waitFor(compileTimeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("SPJ compile timeout");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("SPJ compile failed: " + output);
        }
    }

    private int runChecker(Path checkerBinary, Path input, Path output, Path answer, Path configPath)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(
                checkerBinary.toString(),
                input.toString(),
                output.toString(),
                answer.toString(),
                configPath.toString()
        );
        builder.directory(checkerBinary.getParent().toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String outputStr = readProcessOutput(process);
        boolean finished = process.waitFor(runTimeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("SPJ checker timeout");
        }
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            log.info("SPJ WA: {}", outputStr);
        }
        return exitCode;
    }

    private Path writeConfigFile(Path dir, SpjConfig config) throws IOException {
        StringBuilder builder = new StringBuilder();
        if (config.getIgnoreOrder() != null) {
            builder.append("ignoreOrder=").append(config.getIgnoreOrder()).append("\n");
        }
        if (StringUtils.isNotBlank(config.getCompareUnit())) {
            builder.append("compareUnit=").append(config.getCompareUnit()).append("\n");
        }
        if (config.getFloatEps() != null) {
            builder.append("floatEps=").append(config.getFloatEps()).append("\n");
        }
        Path file = dir.resolve(CONFIG_FILE_NAME);
        Files.write(file, builder.toString().getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private Path writeCaseFile(Path dir, String filename, String content) throws IOException {
        Path file = dir.resolve(filename);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private List<JudgeCaseResult> buildCaseResults(List<JudgeCase> judgeCaseList, boolean accepted) {
        int size = judgeCaseList == null ? 0 : judgeCaseList.size();
        List<JudgeCaseResult> results = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            JudgeCaseResult caseResult = new JudgeCaseResult();
            caseResult.setIndex(i + 1);
            caseResult.setStatus(accepted ? "AC" : "WA");
            results.add(caseResult);
        }
        return results;
    }

    private String readProcessOutput(Process process) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }

    private void deleteDirectory(Path dir) {
        if (dir == null) {
            return;
        }
        try {
            if (!Files.exists(dir)) {
                return;
            }
            Files.walk(dir)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // ignore
                        }
                    });
        } catch (IOException ignored) {
            // ignore
        }
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private Path resolveCheckerBinaryPath() {
        Path path = resolvePath(checkerBinaryPath);
        if (isWindows() && !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe")) {
            return path.resolveSibling(path.getFileName().toString() + ".exe");
        }
        return path;
    }

    private Path resolvePath(String pathStr) {
        Path path = Paths.get(pathStr);
        if (path.isAbsolute()) {
            return path;
        }
        return Paths.get(System.getProperty("user.dir")).resolve(pathStr).normalize();
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }
}
