# #107 로컬 제출 전 확인 재현

최종 갱신: 2026-10-17 UTC. 이 문서는 로컬 격리 검증만 설명한다. 실제 AI, 운영 계정, 실제 OAuth 서비스, Secret 변경, 배포를 사용하지 않는다. 게시 커밋과 정확 HEAD CI 연결은 통합 담당자가 추가한다.

## 확인된 범위와 근거

실제 browser → Spring → PostgreSQL 18/Redis 8 → 응답 경로를 사용했다. 로컬 OIDC/RSA와 실제 게스트 password/proof 검증을 통과하며 precheck provider만 명시 합성 대역이다. 웹의 `docs/verification/issue-107-native-browser.json`에 12/12 PASS, 실제 API 관측 128회, route mock/외부 요청/실모델 호출 0과 실행 전후 소스·runtime manifest·자산 지문 불변을 기록했다. 이것은 실모델 오탐·미탐, 정확도, p50/p95, 비용의 측정이 아니다.

검증은 pending 저장/완료 구분, 명시 선택과 직접 수정 재검사, 넓은 의도 보존(합성 CLEAR), reload·CAS·멱등 재전송, 수동/기한 마감 중 늦은 결과 차단, 마지막 accepted만 실제 batch에 고정, 실제 본인 proof, REPLACE_ONLY 조회 금지, invalid Bearer 우선, 계정 logout 뒤 개인 결과 격리를 포함한다.

웹 저장소의 근거 파일:

- `.tdd/verification/issue-107-native-runtime-source-manifest.json`: 서버 main Kotlin·migration·하네스 216개 파일. 원본 digest `03bb749dad1461b5096a17c7a6bc0dd76bb4fbd437bfb176ac75d7ce044ce9a0`.
- `.tdd/verification/issue-107-native-frozen-class-resource-manifest.json`: 같은 잠금 구간에서 복제한 runtime 파일 1,339개의 SHA-256.
- `.tdd/verification/issue-107-final-native-contract-gate.json`: 최종 실제 OpenAPI와 웹 snapshot의 JSON 의미 동일성 및 준비 로그 연결.
- `docs/verification/issue-107-native-browser.json`: 웹 행동 소스 7개, native outDir의 모든 자산, 실행 script의 SHA-256. 일회성 `/tmp` 경로 이름이 달라지면 재현 실행의 새 근거로 기록한다.

## 실행 준비

서버와 웹의 통합 검증 소스를 각각 checkout하고 의존성을 준비한다. Docker가 사용 가능해야 한다. 서버 하네스는 `AuthEndToEndPostgresTest`의 격리 컨테이너와 로컬 인증 대역을 재사용하며 별도의 운영 자격 증명을 받지 않는다. 웹 의존성은 lockfile 그대로 설치한다.

이 환경에서 JDK17은 `/tmp/meetme-jdk17`, Gradle cache는 `/tmp/meetme-gradle-home`을 사용했다. 다른 환경에서는 해당 설치 경로만 바꾼다. 공유 작업에서는 서버 build/class 복제 사이에 다른 Gradle이 끼어들지 않도록 같은 `/tmp/meetme-gradle.lock`을 사용한다.

```bash
export JAVA_HOME=/tmp/meetme-jdk17
export GRADLE_USER_HOME=/tmp/meetme-gradle-home
export JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts
```

서버 저장소에서 아래 Python 내용을 `/tmp/issue107-native-prepare.py`로 저장한다. `repo`는 현재 서버 checkout을 가리킨다. 컴파일, class/resource 복제, source manifest 작성을 하나의 flock 구간에서 수행한다. 실행 중인 이전 fixture를 먼저 정상 종료해야 한다.

```python
from pathlib import Path
import hashlib, json, shutil, subprocess

repo = Path.cwd()
subprocess.run([
    './gradlew', '--no-daemon', '--console=plain', '-I',
    'docs/verification/issue-107-native-browser.init.gradle',
    'issue107NativeRuntimeClasspath',
], check=True)
frozen = Path('/tmp/issue107-native-final-frozen-runtime')
frozen.mkdir(exist_ok=True)
runtime = []
parts = Path('/tmp/meetme-issue107-native-runtime.classpath').read_text().split(':')
for index, part in enumerate(parts):
    source = Path(part)
    if source.is_relative_to(repo / 'build') and source.exists():
        target = frozen / f'{index}-{source.name}'
        if source.is_dir():
            if target.exists():
                shutil.rmtree(target)
            shutil.copytree(source, target)
        else:
            shutil.copy2(source, target)
        runtime.append(str(target))
    else:
        runtime.append(part)
main = 'com/meetme/server/submission/adapter/input/web/PreSubmissionCheckNativeBrowserServerKt.class'
assert any((Path(part) / main).exists() for part in runtime)
Path('/tmp/issue107-native-final-frozen-runtime.classpath').write_text(':'.join(runtime))
files = sorted(list(repo.glob('src/main/**/*.kt'))
    + list(repo.glob('src/main/resources/db/migration/*.sql'))
    + [repo / 'src/test/kotlin/com/meetme/server/submission/adapter/input/web/PreSubmissionCheckNativeBrowserServer.kt'])
manifest = {str(path.relative_to(repo)): hashlib.sha256(path.read_bytes()).hexdigest()
    for path in files}
Path('/tmp/issue107-native-runtime-manifest.json').write_text(json.dumps(manifest, indent=2))
```

```bash
flock /tmp/meetme-gradle.lock python3 /tmp/issue107-native-prepare.py
rm -f /tmp/issue107-native-stop
"$JAVA_HOME/bin/java" --add-modules=jdk.httpserver \
  -Dmeetme.issue107.native.stop-file=/tmp/issue107-native-stop \
  -cp "$(cat /tmp/issue107-native-final-frozen-runtime.classpath)" \
  com.meetme.server.submission.adapter.input.web.PreSubmissionCheckNativeBrowserServerKt
```

live 서버의 수명 동안 Gradle lock을 잡지 않는다. 준비 후 소스가 바뀌면 기존 근거를 새 소스의 성공으로 표시하지 않고 정상 종료·준비·실행을 다시 수행한다.

## 웹과 독립 시나리오

웹 저장소에서 native 전용 자산을 별도 outDir에 생성한다. `localhost`는 로컬 OIDC redirect/origin 계약의 일부다.

```bash
VITE_API_BASE_URL=http://localhost:18920 pnpm exec vite build --configLoader runner \
  --outDir /tmp/meetme-precheck-native-dist
pnpm exec vite preview --configLoader runner --outDir /tmp/meetme-precheck-native-dist \
  --host localhost --port 18921 --strictPort
```

별도 터미널에서 fixture health의 `fixture=issue107-isolated-native-v1`, `production=false`, API/web origin을 확인한 다음 실행한다. script는 loopback만 허용하며 실제 API 응답을 대체하지 않는다. 실행 중 runtime/source/native 자산을 바꾸지 않는다.

```bash
curl --fail http://127.0.0.1:18922/health
CHROMIUM_PATH=/usr/bin/chromium node scripts/verify-precheck-native.mjs
```

테스트 제어는 127.0.0.1:18922의 별도 HttpServer에만 있다. `/reset`은 격리 방만 초기화하고 실제 로그인 계정/session은 유지한다. `/provider`, `/provider/release`, `/worker/run-one`은 명시 합성 결과와 provider barrier를 제어한다. `/room/deadline`은 격리 방의 시각 경계만 바꾼다. `/room/run-deadline-closure`는 실제 `DeadlineClosureScheduler.closeDueRooms()` transactional bean을 한 번 실행한다. collection_status를 SQL로 CLOSED로 바꾸지 않는다. 이 명시 tick은 기존 60초 주기를 기다리지 않고 실제 deadline/freeze 경로를 검증하기 위한 test-only 제어다.

## 종료와 남은 경계

`touch /tmp/issue107-native-stop`으로 API/control/context와 컨테이너를 정상 종료한다. 웹 preview도 종료한다. 재시작은 이전 health가 내려간 것을 확인한 뒤 정확한 stop file 하나만 제거하고 위 준비부터 반복한다. 로그에 토큰·password·실제 개인 입력을 출력하지 않는다.

실모델 품질·실제 OAuth 공급자·운영 Secret/DB·배포와 정확 게시 HEAD CI는 이 로컬 결과와 별도다. 실제 provider의 출력 상한 capability가 검증되지 않으면 fail-closed하므로 로컬 합성 성공을 운영 활성화 준비 완료로 간주하지 않는다.

## 필수 전체 게이트와 생산 artifact

최종 unchanged `tdd_guard`가 self-tests·ktlintCheck·assemble·전체 test를 통과했다. 실제 새 XML177개 집계는 1,197개 중1,193 PASS·기존4 SKIP·실패/오류0이고, 검사 시작/종료의417 source/test/hook 지문이 동일하다. 근거는 `.tdd/verification/issue107-final-server-gate.json` 및 `issue107-final-server-source-manifest.json`이다. skip은 기존 OpenAPI debug/export3개와 미승인 live model probe1개다.

생산 boot JAR SHA-256은 `6f95bfa110ce1d26401a0a2906d8ac9852085582dc884a1112b1869641149d21`이고, plain JAR는 `53e6d0f89b354a39238ab8d9fe016f356731af66eeee83e2133ecc4253a27318`이다. 두 JAR에서 V16 포함·test-only native harness 미포함을 확인했다. 이 artifact 검증과 위 native 테스트의 frozen classpath 런타임은 구분한다. 게시 commit/CI는 PR receipt와 통합 Worklog에 별도로 연결한다.
