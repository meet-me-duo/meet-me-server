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

test("main push CI 성공만 자동 release publication을 시작한다", () => {
    const source = workflow();

    assert.match(source, /workflow_run:\s*\n\s+workflows:\s*\[CI]\s*\n\s+types:\s*\[completed]\s*\n\s+branches:\s*\[main]/);
    assert.match(source, /workflow_dispatch:/);
    assert.match(source, /github\.event\.workflow_run\.conclusion == 'success'/);
    assert.match(source, /github\.event\.workflow_run\.event == 'push'/);
    assert.match(source, /github\.event\.workflow_run\.head_branch == 'main'/);
    const build = source.match(/^  build-publish:\s*\n([\s\S]*?)(?=^  [A-Za-z][\w-]*:|$(?![\s\S]))/m);
    assert.ok(build, "Normal main CI must retain automatic build publication");
    assert.doesNotMatch(build[0], /ssm\s+send-command|host-release-guard|install-host-release-guard/);
});

test("자동 publication은 성공한 CI의 정확한 commit SHA와 run attempt를 사용한다", () => {
    const source = workflow();

    assert.match(
        source,
        /SOURCE_SHA: \$\{\{\s*github\.event\.workflow_run\.head_sha\s*}}/,
    );
    assert.match(source, /ref: \$\{\{ env\.SOURCE_SHA }}/);
    assert.match(source, /git-\$\{\{ env\.SOURCE_SHA }}-\$\{\{ github\.run_id }}-\$\{\{ github\.run_attempt }}/);
    const build = source.match(/^  build-publish:\s*\n([\s\S]*?)(?=^  [A-Za-z][\w-]*:|$(?![\s\S]))/m);
    assert.ok(build);
    assert.doesNotMatch(build[0], /github\.sha/, "Automatic publication must never substitute a different event SHA");
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
        /github\.event_name == 'workflow_dispatch'[\s\S]*?github\.ref == 'refs\/heads\/main'/,
    );
});
