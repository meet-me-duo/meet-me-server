const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

// Issue #99: an isolated ARM64 CI build is proof of a local image, never a deploy approval.
// Source contract checks only: no Docker, AWS, secrets, Java or application execution.
const repository = path.resolve(__dirname, '../..');
const ci = () => fs.readFileSync(path.join(repository, '.github/workflows/ci.yml'), 'utf8');
const helper = () => fs.readFileSync(path.join(repository, 'deploy/scripts/verify-built-image.sh'), 'utf8');
const dockerfile = () => fs.readFileSync(path.join(repository, 'Dockerfile'), 'utf8');
function imageJob() {
  const source = ci();
  const match = source.match(/^  built-image:\s*\n([\s\S]*?)(?=^  [A-Za-z][\w-]*:|$(?![\s\S]))/m);
  assert.ok(match, 'CI must declare its independent built-image verification job');
  return match[0];
}

test('image CI checks out the exact PR head and removes persisted credentials', () => {
  const job = imageJob();
  assert.match(job, /CI_SOURCE_SHA:\s*\$\{\{\s*github\.event\.pull_request\.head\.sha\s*\|\|\s*github\.sha\s*}}/);
  assert.match(job, /uses:\s*actions\/checkout@/);
  assert.match(job, /ref:\s*\$\{\{\s*env\.CI_SOURCE_SHA\s*}}/);
  assert.match(job, /persist-credentials:\s*false/);
  assert.doesNotMatch(job, /^\s+needs:/m, 'Image verification must run independently of quality');
});

test('image CI has read-only repository permissions without production authority', () => {
  const job = imageJob();
  assert.match(job, /permissions:\s*\n\s+contents:\s*read/);
  assert.doesNotMatch(job, /id-token:|secrets\.|environment:|aws-actions\/|amazon-ecr|role-to-assume|AWS_ACCESS_KEY|AWS_SECRET_ACCESS_KEY/);
  assert.doesNotMatch(job, /\baws\s|\bterraform\s|deploy-release|host-release-guard|send-command|docker\s+(push|login)|workflow_dispatch/);
});

test('CI builds and loads an ARM64 local tag using the existing QEMU pattern', () => {
  const job = imageJob();
  assert.match(job, /CI_IMAGE_TAG:\s*meet-me-ci:\$\{\{\s*github\.event\.pull_request\.head\.sha\s*\|\|\s*github\.sha\s*}}/);
  assert.match(job, /uses:\s*docker\/setup-qemu-action@/);
  assert.match(job, /uses:\s*docker\/setup-buildx-action@/);
  assert.match(job, /uses:\s*docker\/build-push-action@/);
  assert.match(job, /platforms:\s*linux\/arm64\s*$/m);
  assert.match(job, /context:\s*\.\s*$/m);
  assert.match(job, /tags:\s*\$\{\{\s*env\.CI_IMAGE_TAG\s*}}/);
  assert.match(job, /load:\s*true/);
  assert.match(job, /push:\s*false/);
  assert.match(job, /provenance:\s*false/);
  assert.doesNotMatch(job, /push:\s*true|secrets:|secret-envs:|build-args:|ECR_REPOSITORY/);
});

test('CI verifies the loaded image and uploads only short-lived sanitized proof', () => {
  const job = imageJob();
  assert.match(job, /bash deploy\/scripts\/verify-built-image\.sh "\$CI_IMAGE_TAG"\s*>\s*built-image-verification\.json/);
  assert.match(job, /uses:\s*actions\/upload-artifact@/);
  const upload = job.slice(job.indexOf('uses: actions/upload-artifact@'));
  assert.match(upload, /path:\s*built-image-verification\.json\s*$/m);
  assert.match(upload, /retention-days:\s*3/);
  assert.match(upload, /if-no-files-found:\s*error/);
  assert.doesNotMatch(upload, /\.env|\*|\.log|stderr|runtime-env|secret/);
  assert.doesNotMatch(job, /(?:cat|tee)\s+.*(?:env|stderr|stdout)|printenv|docker\s+inspect/);
});

test('the normal CI quality gate runs the independent image workflow contract', () => {
  assert.match(ci(), /node --test[^\n]*deploy\/tests\/built-image-workflow\.test\.cjs/);
});

test('the packaged runtime keeps the non-root entrypoint, application JAR and JRE 17', () => {
  const source = dockerfile();
  assert.match(source, /FROM eclipse-temurin:17-jre-jammy AS runtime/);
  assert.match(source, /USER 10001:10001/);
  assert.match(source, /COPY --from=build --chown=meetme:meetme[^\n]+\/app\/app\.jar/);
  assert.match(source, /COPY --chown=meetme:meetme deploy\/container-entrypoint\.sh \/app\/container-entrypoint\.sh/);
  assert.match(source, /chmod 0755 \/app\/container-entrypoint\.sh/);
  assert.match(source, /ENTRYPOINT \["\/app\/container-entrypoint\.sh"\]/);
});

test('every image execution uses an immutable local ID with network and privilege isolation', () => {
  const source = helper();
  assert.match(source, /config_id="\$\{BASH_REMATCH\[1]}"\s*\nimage="\$config_id"/);
  assert.equal((source.match(/docker run\b/g) || []).length, 1, 'One isolated runner must cover every probe');
  assert.match(source, /docker run[^\n]*--rm --pull never --network none --read-only/);
  assert.match(source, /--security-opt no-new-privileges --cap-drop ALL/);
  assert.match(source, /--tmpfs \/tmp:rw,nosuid,size=128m/);
  assert.doesNotMatch(source, /--privileged|--network host|--user 0|docker\s+(push|pull|login|system|container prune)|\baws\s/);
  assert.match(source, /for name in "\$\{containers\[@]}"; do docker rm -f "\$name"/);
});

test('image verification checks actual UID, file ownership, mode and source entrypoint bytes', () => {
  const source = helper();
  assert.match(source, /10001:10001/);
  assert.match(source, /test -f \/app\/app\.jar && test -s \/app\/app\.jar/);
  assert.match(source, /stat -c %u:%g:%a \/app\/container-entrypoint\.sh/);
  assert.match(source, /10001:10001:755/);
  assert.match(source, /sha256sum "\$repository_root\/deploy\/container-entrypoint\.sh"/);
  assert.match(source, /sha256sum \/app\/container-entrypoint\.sh/);
  assert.match(source, /id -u/);
});

test('valid legacy and required modes use real Java while rejected commands use a safe sentinel', () => {
  const source = helper();
  assert.match(source, /run_command -- java -version/);
  assert.match(source, /MEETME_RUNTIME_PROVIDER_MODE=gemini-only -- java -version/);
  assert.match(source, /MEETME_RUNTIME_PROVIDER_MODE=gemini-luna-required/);
  assert.match(source, /test "\$OPENAI_API_KEY" = "\$1"; exec java -version/);
  assert.match(source, /for command in server migrate custom/);
  assert.match(source, /JAVA_STARTED/);
  assert.match(source, /Required Luna runtime key is missing or invalid/);
  assert.match(source, /Runtime provider mode is invalid/);
  assert.match(source, /contains space/);
  assert.match(source, /contains\\tTAB/);
  assert.match(source, /contains\\nLF/);
  assert.match(source, /unicode-키/);
  assert.match(source, /cmp -s "\$scratch\/expected-error" "\$scratch\/stderr"/);
});

test('proof contains local image identity and source provenance without release approval or values', () => {
  const source = helper();
  assert.match(source, /git -C "\$repository_root" rev-parse HEAD/);
  assert.match(source, /"protocol":"meetme-built-image-v1"/);
  assert.match(source, /"sourceSha":"%s"/);
  assert.match(source, /"configImageId":"%s"/);
  assert.match(source, /"architecture":"arm64"/);
  assert.match(source, /"noEcrDigestApproval":true/);
  assert.match(source, /set \+x/);
  assert.doesNotMatch(source, /(?:cat|tee) "?\$scratch\/(stdout|stderr)|(?:echo|printf)[^\n]*\$(synthetic_key|unsafe)|printenv|env\s*$/m);
});

test('the image job disables implicit Docker build records and build summaries', () => {
  const job = imageJob();
  const jobEnvironment = job.match(/^    env:\s*\n((?:^      [^\n]*\n)+)/m);
  assert.ok(jobEnvironment, 'The image job must declare its own safe build environment');
  assert.match(jobEnvironment[1], /^      DOCKER_BUILD_RECORD_UPLOAD:\s*["']false["']\s*$/m);
  assert.match(jobEnvironment[1], /^      DOCKER_BUILD_SUMMARY:\s*["']false["']\s*$/m);
});
