const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const workflowPath = path.join(
    __dirname,
    "..",
    "workflows",
    "deploy.yml",
);

function workflow() {
    return fs.readFileSync(workflowPath, "utf8");
}

test("main push CI 성공만 운영 자동 배포를 시작한다", () => {
    const source = workflow();

    assert.match(source, /workflow_run:\s*\n\s+workflows:\s*\[CI]\s*\n\s+types:\s*\[completed]\s*\n\s+branches:\s*\[main]/);
    assert.match(source, /workflow_dispatch:/);
    assert.match(source, /github\.event\.workflow_run\.conclusion == 'success'/);
    assert.match(source, /github\.event\.workflow_run\.event == 'push'/);
    assert.match(source, /github\.event\.workflow_run\.head_branch == 'main'/);
});

test("자동 배포는 성공한 CI의 정확한 commit SHA를 사용한다", () => {
    const source = workflow();

    assert.match(
        source,
        /SOURCE_SHA: \$\{\{ github\.event_name == 'workflow_run' && github\.event\.workflow_run\.head_sha \|\| github\.sha }}/,
    );
    assert.match(source, /ref: \$\{\{ env\.SOURCE_SHA }}/);
    assert.match(source, /git-\$\{\{ env\.SOURCE_SHA }}-\$\{\{ github\.run_id }}/);
    assert.equal(
        (source.match(/github\.sha/g) ?? []).length,
        1,
        "github.sha는 수동 실행 fallback에서만 사용해야 한다",
    );
});

test("운영 배포는 직렬화하며 진행 중 실행을 취소하지 않는다", () => {
    const source = workflow();

    assert.match(source, /group: production-deploy/);
    assert.match(source, /cancel-in-progress: false/);
    assert.doesNotMatch(source, /cancel-in-progress: true/);
});

test("수동 복구 배포는 main ref에서만 허용한다", () => {
    const source = workflow();

    assert.match(
        source,
        /if: github\.event_name == 'workflow_dispatch' && github\.ref != 'refs\/heads\/main'/,
    );
});
