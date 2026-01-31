package biny.biny.judge.codesandbox.model;

import java.util.List;
import lombok.Data;

@Data
public class SpjCheckResult {

    private boolean accepted;

    private List<JudgeCaseResult> caseResults;

    private String message;
}
