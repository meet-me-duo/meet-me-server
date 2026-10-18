import { spawn, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createReadStream, closeSync, existsSync, openSync } from 'node:fs';
import { copyFile, mkdir, mkdtemp, readFile, readdir, realpath, rm, stat, symlink, writeFile } from 'node:fs/promises';
import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, extname, join, resolve, sep } from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { validateManagedReadiness, validateManagedCompletion } from './runner-contract.mjs';
import { validateLocalDockerTarget } from './docker-target.mjs';
import { validateManagedDockerBinding } from './docker-daemon-binding.mjs';

process.umask(0o077);
const here = dirname(fileURLToPath(import.meta.url));
const EXPECTED_WEB = '3c6fb446e3a6e20aec5fd2f9e1eb78233c6f09e6';
const EXPECTED_SERVER = '6046baf66e49e9382248cd3fb8adb0a8879a7e40';
const POLICY_ORIGIN = '27de97e137d23a2920d425ea521ee33ec0996a8d';
const MANAGED_DOCKER_ENDPOINT = 'unix:///var/run/docker.sock';
const GRAPH_KEYS = ['provider', 'classifier', 'budget', 'repository', 'gate'];
const origins = { web: 'https://localhost:19443', api: 'https://localhost:19444', denied: 'https://localhost:19445', control: 'http://127.0.0.1:19442' };
const permittedSuites = ['new-managed8', 'original-host8', 'original-recovery6'];
function fail(code) { const error = new Error(code); error.code = code; throw error; }
function parseArgs(argv) {
  const value = {};
  for (let i = 0; i < argv.length; i += 2) {
    const key = argv[i];
    if (!['--manifest', '--web', '--suite'].includes(key) || !argv[i + 1] || value[key]) fail('INVALID_ARGUMENTS');
    value[key] = argv[i + 1];
  }
  if (!value['--manifest']) fail('MANIFEST_REQUIRED');
  const suite = value['--suite'] ?? 'new-managed8';
  if (!permittedSuites.includes(suite)) fail('INVALID_SUITE');
  return { manifest: resolve(value['--manifest']), web: resolve(value['--web'] ?? '/workspace/issue130-managed-browser-integration/web'), suite };
}
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const equal = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const sleep = ms => new Promise(done => setTimeout(done, ms));
const envNames = ['PATH', 'LANG', 'LC_ALL', 'PLAYWRIGHT_BROWSERS_PATH'];
const childEnv = { PYTHONDONTWRITEBYTECODE: '1', ...Object.fromEntries(envNames.filter(key => process.env[key] !== undefined).map(key => [key, process.env[key]])), JAVA_HOME: '/tmp/meetme-jdk17', JAVA_TOOL_OPTIONS: '-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts', OPENAI_API_KEY: '', GEMINI_API_KEY: '', GOOGLE_API_KEY: '', ANTHROPIC_API_KEY: '', HTTP_PROXY: '', HTTPS_PROXY: '', ALL_PROXY: '', http_proxy: '', https_proxy: '', all_proxy: '' };
function run(command, args, cwd, options = {}) {
  const result = spawnSync(command, args, { cwd, env: childEnv, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024, timeout: 120_000, ...options });
  if (result.error || result.status !== 0) fail('LOCAL_TOOL_FAILED');
  return (result.stdout ?? '').trim();
}
async function files(directory, prefix = '', ignoredTopLevel = []) {
  const entries = await readdir(join(directory, prefix), { withFileTypes: true });
  return (await Promise.all(entries.map(entry => {
    if (!prefix && ignoredTopLevel.includes(entry.name)) return [];
    const relative = prefix ? `${prefix}/${entry.name}` : entry.name;
    if (entry.isSymbolicLink()) fail('UNEXPECTED_ASSET_SYMLINK');
    return entry.isDirectory() ? files(directory, relative, ignoredTopLevel) : [relative];
  }))).flat().sort();
}
async function hashes(root, paths) { return Object.fromEntries(await Promise.all([...paths].sort().map(async path => [path, digest(await readFile(join(root, path)))]))); }
async function jsonFile(path) { return JSON.parse(await readFile(path, 'utf8')); }
async function getJson(url) {
  return new Promise(resolveResult => {
    const request = http.get(url, response => {
      const chunks = []; let length = 0;
      response.on('data', chunk => { length += chunk.length; if (length > 131072) request.destroy(); else chunks.push(chunk); });
      response.on('error', () => resolveResult(null));
      response.on('end', () => { try { resolveResult(response.statusCode === 200 ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : null); } catch { resolveResult(null); } });
    });
    request.setTimeout(2000, () => request.destroy()); request.on('error', () => resolveResult(null));
  });
}
async function portOpen(port) {
  return new Promise(resolveOpen => {
    const socket = net.connect({ host: '127.0.0.1', port });
    socket.once('connect', () => { socket.destroy(); resolveOpen(true); });
    socket.once('error', () => resolveOpen(false)); socket.setTimeout(500, () => { socket.destroy(); resolveOpen(false); });
  });
}
async function main() {
  const options = parseArgs(process.argv.slice(2));
  const work = await mkdtemp(join(tmpdir(), 'meetme-managed-browser-'));
  const backendWork = join(work, 'backend'); const dist = join(work, 'dist'); const staging = join(work, 'playwright');
  const metadata = { schemaVersion: 1, scope: options.suite, completed: false, productionAccess: false, actualModelCalls: 0, actualExternalOAuthCalls: 0, checks: {}, limitations: ['Synthetic transport exercises real managed TwoStage/worker/ledger; it does not measure model quality, paid cost or real OAuth.', 'Final analysis dispatch stays disabled: close/frozen accepted cohort only.', 'New8, historical original8 and recovery6 are separate scopes; original failures and native autofocus residual are not erased.', 'Desktop and mobile-sized Chromium viewports are not physical mobile devices.'] };
  const listeners = []; const ownedPorts = new Set([19440, 19442, 19443, 19444, 19445, 19446]);
  let backend; let backendClosed; let activeTests; let browserProbe; let backendLog; let cleanupPromise; let observations; let pins; let webFiles; let inputBefore; let assetsBefore; let health;
  async function toolHashes() {
    const paths = (await files(here, '', ['__pycache__'])).filter(path => !path.endsWith('.pyc'));
    return hashes(here, paths);
  }
  async function provenance(manifest) {
    const webHead = run('git', ['rev-parse', 'HEAD'], options.web);
    if (webHead !== EXPECTED_WEB || run('git', ['status', '--porcelain'], options.web) !== '') fail('WEB_PIN_MISMATCH');
    const directory = dirname(options.manifest);
    return { webHead, serverHead: manifest.serverHead, webFiles: await hashes(options.web, webFiles), serverFiles: await hashes(join(directory, 'source'), Object.keys(manifest.sourceFiles)), runtimeFiles: await hashes(join(directory, 'runtime'), Object.keys(manifest.runtimeFiles)) };
  }
  async function enforceLocalDocker() {
    const optional = key => process.env[key] === undefined ? null : process.env[key];
    const evidence = { environmentHost: optional('DOCKER_HOST'), environmentContext: optional('DOCKER_CONTEXT'), tlsVerify: optional('DOCKER_TLS_VERIFY'), certificatePath: optional('DOCKER_CERT_PATH') };
    // Validate ambient host/credential syntax before even local context metadata inspection.
    validateLocalDockerTarget({ ...evidence, contextEndpoint: evidence.environmentHost || 'unix:///var/run/docker.sock' });
    const inspectEnv = { ...childEnv };
    if (process.env.HOME) inspectEnv.HOME = process.env.HOME;
    if (process.env.DOCKER_CONFIG) inspectEnv.DOCKER_CONFIG = process.env.DOCKER_CONFIG;
    const args = ['context', 'inspect', '--format', '{{json .Endpoints.docker.Host}}'];
    if (evidence.environmentContext) args.push('--', evidence.environmentContext);
    evidence.contextEndpoint = JSON.parse(run('docker', args, here, { env: inspectEnv }));
    validateLocalDockerTarget(evidence);
    validateManagedDockerBinding(evidence.contextEndpoint, MANAGED_DOCKER_ENDPOINT);
    if (evidence.environmentHost) validateManagedDockerBinding(evidence.environmentHost, MANAGED_DOCKER_ENDPOINT);
    const endpoint = MANAGED_DOCKER_ENDPOINT;
    if (endpoint.startsWith('unix://')) {
      const socket = endpoint.slice('unix://'.length);
      if (!(await stat(socket)).isSocket() || !['/run/docker.sock', '/var/run/docker.sock'].includes(await realpath(socket))) fail('LOCAL_DOCKER_REQUIRED');
    }
    const hostOverride = process.env.TESTCONTAINERS_HOST_OVERRIDE;
    if (hostOverride && !['127.0.0.1', 'localhost', '::1'].includes(hostOverride)) fail('LOCAL_DOCKER_REQUIRED');
    const socketOverride = process.env.TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE;
    if (socketOverride && !['/run/docker.sock', '/var/run/docker.sock'].includes(socketOverride)) fail('LOCAL_DOCKER_REQUIRED');
    childEnv.DOCKER_HOST = endpoint;
    childEnv.DOCKER_CONTEXT = '';
    if (hostOverride) childEnv.TESTCONTAINERS_HOST_OVERRIDE = hostOverride;
    if (socketOverride) childEnv.TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE = socketOverride;
    metadata.dockerTarget = { local: true, contextMetadataInspectedWithoutDaemonConnection: true, endpointKind: endpoint.startsWith('unix://') ? 'unix' : 'literal-loopback' };
  }
  async function verifiedBackend(readinessPath) {
    const args = [join(here, 'runtime_pin.py'), '--manifest', options.manifest, '--verify-only'];
    if (readinessPath) args.push('--readiness', readinessPath);
    return JSON.parse(run('python', args, here));
  }
  async function tlsMaterial() {
    run('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', join(work, 'ca.key'), '-out', join(work, 'ca.pem'), '-days', '1', '-subj', '/CN=Meet me ephemeral integration CA', '-addext', 'basicConstraints=critical,CA:TRUE,pathlen:0', '-addext', 'keyUsage=critical,keyCertSign,cRLSign'], here);
    run('openssl', ['req', '-new', '-newkey', 'rsa:2048', '-nodes', '-keyout', join(work, 'localhost.key'), '-out', join(work, 'localhost.csr'), '-subj', '/CN=localhost'], here);
    await writeFile(join(work, 'leaf.ext'), 'basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:localhost,IP:127.0.0.1\n');
    run('openssl', ['x509', '-req', '-in', join(work, 'localhost.csr'), '-CA', join(work, 'ca.pem'), '-CAkey', join(work, 'ca.key'), '-CAcreateserial', '-out', join(work, 'localhost.pem'), '-days', '1', '-extfile', join(work, 'leaf.ext')], here);
    run('openssl', ['verify', '-CAfile', join(work, 'ca.pem'), '-verify_hostname', 'localhost', join(work, 'localhost.pem')], here);
    metadata.checks.tlsChainAndHostnameVerified = true;
    return { key: await readFile(join(work, 'localhost.key')), cert: await readFile(join(work, 'localhost.pem')) };
  }
  async function listen(server, port) {
    server.on('connection', socket => socket.on('error', () => { metadata.fixtureSocketErrors = (metadata.fixtureSocketErrors ?? 0) + 1; }));
    await new Promise((done, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', done); }); listeners.push(server);
  }
  function cleanup() {
    cleanupPromise ??= (async () => {
      if (activeTests?.pid) { try { process.kill(-activeTests.pid, 'SIGTERM'); } catch { /* Only this owned group. */ } }
      await browserProbe?.close().catch(() => {});
      await Promise.all(listeners.map(server => new Promise(done => { server.closeAllConnections(); server.close(done); })));
      if (backend) {
        if (existsSync(backendWork)) await writeFile(join(backendWork, 'stop'), '');
        let ended = await Promise.race([backendClosed.then(() => true), sleep(15000).then(() => false)]);
        if (!ended) { try { process.kill(-backend.pid, 'SIGTERM'); } catch { /* Already exited. */ } ended = await Promise.race([backendClosed.then(() => true), sleep(5000).then(() => false)]); }
        if (!ended) { try { process.kill(-backend.pid, 'SIGKILL'); } catch { /* Already exited. */ } await Promise.race([backendClosed, sleep(5000)]); }
      }
      if (backendLog !== undefined) closeSync(backendLog);
      await Promise.all(['ca.key', 'localhost.key', 'profiles', 'untrusted-profile', 'playwright'].map(path => rm(join(work, path), { recursive: true, force: true })));
      const registryPath = join(backendWork, 'owned-resources.json');
      const registry = existsSync(registryPath) ? await jsonFile(registryPath) : null;
      let containersRemaining = 0;
      if (registry) {
        if (registry.schemaVersion !== 1 || !Array.isArray(registry.containerIds) || !registry.containerIds.every(id => /^[a-f0-9]{12,64}$/.test(id)) || !Array.isArray(registry.listenerPorts) || !registry.listenerPorts.every(port => Number.isInteger(port) && port > 0 && port <= 65535)) fail('INVALID_OWNED_RESOURCE_REGISTRY');
        for (const port of registry.listenerPorts) ownedPorts.add(port);
        for (const id of registry.containerIds) {
          const result = spawnSync('docker', ['--host', MANAGED_DOCKER_ENDPOINT, 'inspect', '--format', '{{.Id}}', id], { env: childEnv, encoding: 'utf8', timeout: 5000 });
          if (result.status === 0) containersRemaining += 1;
          else if (!/No such (object|container)/i.test(result.stderr ?? '')) fail('OWNED_CONTAINER_CHECK_FAILED');
        }
      } else if (backend) { containersRemaining = 1; }

      let privateRemaining = 0;
      if (registry) {
        if (!Array.isArray(registry.privateMaterialPaths)) fail('INVALID_OWNED_RESOURCE_REGISTRY');
        for (const path of registry.privateMaterialPaths) {
          const actual = resolve(path); if (!actual.startsWith(backendWork + sep)) fail('INVALID_OWNED_RESOURCE_REGISTRY');
          if (existsSync(actual)) privateRemaining += 1;
        }
      }
      const listenersRemaining = (await Promise.all([...ownedPorts].map(portOpen))).filter(Boolean).length;
      metadata.cleanup = { ownedContainersRemaining: containersRemaining, ownedListenersRemaining: listenersRemaining, privateTempMaterialRemaining: privateRemaining };
    })(); return cleanupPromise;
  }
  for (const [signal, code] of [['SIGINT', 130], ['SIGTERM', 143]]) process.once(signal, () => {
    metadata.failureCode = 'INTERRUPTED'; metadata.completed = false;
    void cleanup().then(async () => { await writeFile(join(work, 'evidence.json'), JSON.stringify(metadata, null, 2) + '\n'); process.exit(code); });
  });
  try {
    if (Number(process.versions.node.split('.')[0]) < 24) fail('NODE24_REQUIRED');
    await enforceLocalDocker();
    run('docker', ['--host', MANAGED_DOCKER_ENDPOINT, 'info', '--format', '{{.ServerVersion}}'], here);
    for (const port of ownedPorts) if (await portOpen(port)) fail('OWNED_PORT_ALREADY_IN_USE');
    webFiles = run('git', ['ls-files'], options.web).split('\n').filter(Boolean);
    if (run('git', ['rev-parse', 'HEAD'], options.web) !== EXPECTED_WEB || run('git', ['status', '--porcelain'], options.web)) fail('WEB_PIN_MISMATCH');
    const manifest = await jsonFile(options.manifest);
    if (manifest.serverHead !== EXPECTED_SERVER || manifest.policyOriginHead !== POLICY_ORIGIN) fail('SERVER_PIN_MISMATCH');
    metadata.backendVerified = await verifiedBackend();
    const manifestHash = digest(await readFile(options.manifest));
    metadata.manifestSha256 = manifestHash; metadata.toolSha256Before = await toolHashes();
    inputBefore = await provenance(manifest);
    pins = { schemaVersion: 1, web: { head: EXPECTED_WEB, files: inputBefore.webFiles }, server: { head: EXPECTED_SERVER, files: inputBefore.serverFiles }, runtime: { files: inputBefore.runtimeFiles }, graph: Object.fromEntries(GRAPH_KEYS.map(key => [key, manifest.graph[key]])), scenarios: ['M01', 'M02', 'M03', 'M04'], projects: ['desktop', 'mobile'] };
    await writeFile(join(work, 'pins.json'), JSON.stringify(pins, null, 2) + '\n');
    const buildLog = openSync(join(work, 'build.log'), 'w');
    try {
      run('node', ['node_modules/typescript/bin/tsc', '-b'], options.web, { stdio: ['ignore', buildLog, buildLog] });
      run('node', ['node_modules/vite/bin/vite.js', 'build', '--configLoader', 'runner', '--outDir', dist], options.web, { env: { ...childEnv, VITE_API_BASE_URL: origins.api }, stdio: ['ignore', buildLog, buildLog] });
    } finally { closeSync(buildLog); }
    assetsBefore = await hashes(dist, await files(dist));
    backendLog = openSync(join(work, 'backend.log'), 'w');
    backend = spawn('python', [join(here, 'runtime_pin.py'), '--manifest', options.manifest, '--work-dir', backendWork], { cwd: here, env: childEnv, detached: true, stdio: ['ignore', backendLog, backendLog] });
    backendClosed = new Promise(done => { backend.once('error', done); backend.once('close', done); });
    const deadline = Date.now() + 120000; let readiness;
    while (Date.now() < deadline) {
      if (backend.exitCode !== null || backend.signalCode !== null) fail('BACKEND_EXITED_BEFORE_READY');
      readiness = await getJson(`${origins.control}/managed/readiness`);
      if (readiness) break;
      await sleep(250);
    }
    if (!readiness) fail('BACKEND_STARTUP_TIMEOUT');
    const readinessPath = join(work, 'backend-readiness.json'); await writeFile(readinessPath, JSON.stringify(readiness) + '\n');
    metadata.backendReadyVerified = await verifiedBackend(readinessPath); metadata.backendReadinessSha256 = digest(await readFile(readinessPath));
    health = await getJson(`${origins.control}/health`);
    if (!health || health.fixture !== 'issue123-host-current-v1' || health.implementation !== 'issue130-managed-browser-v1' || health.primary_provider_fake !== false || health.production !== false || health.api_origin !== origins.api || health.web_origin !== origins.web || !/^http:\/\/127\.0\.0\.1:\d+$/.test(health.oidc_origin) || !/^[a-f0-9]{12,64}$/.test(health.db_container ?? '')) fail('BACKEND_COMPATIBILITY_HEALTH_MISMATCH');
    ownedPorts.add(Number(new URL(health.oidc_origin).port));
    observations = { provenanceBefore: inputBefore, health: { ...readiness, graph: Object.fromEntries(GRAPH_KEYS.map(key => [key, readiness.graph[key]])) }, outbound: readiness.outbound, credentials: readiness.credentials };
    validateManagedReadiness(pins, observations); metadata.checks.managedReadiness = true;
    const tls = await tlsMaterial();
    const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.json': 'application/json' };
    await listen(https.createServer(tls, async (request, response) => {
      try {
        const path = decodeURIComponent(new URL(request.url, origins.web).pathname); let target = resolve(dist, `.${path}`);
        if (!target.startsWith(dist + sep) || !existsSync(target) || !extname(target)) target = join(dist, 'index.html');
        const actual = await realpath(target); if (!actual.startsWith(dist + sep) || !(await stat(actual)).isFile()) { response.writeHead(403); response.end(); return; }
        response.writeHead(200, { 'Content-Type': mime[extname(actual)] ?? 'application/octet-stream', 'Cache-Control': 'no-store' }); createReadStream(actual).pipe(response);
      } catch { response.writeHead(404); response.end(); }
    }), 19443);
    await listen(https.createServer(tls, (request, response) => {
      const upstream = http.request({ hostname: '127.0.0.1', port: 19440, method: request.method, path: request.url, headers: request.headers }, remote => { response.writeHead(remote.statusCode, remote.headers); remote.pipe(response); });
      upstream.on('error', () => { if (!response.headersSent) response.writeHead(502); response.end(); }); request.on('error', () => upstream.destroy()); response.on('error', () => upstream.destroy()); request.pipe(upstream);
    }), 19444);
    await listen(https.createServer(tls, (_request, response) => { response.writeHead(200, { 'Content-Type': 'text/html' }); response.end('<!doctype html><title>Unapproved local origin</title>'); }), 19445);
    const deny = http.createServer((_request, response) => { response.writeHead(403); response.end(); });
    deny.on('connect', (_request, socket) => socket.end('HTTP/1.1 403 Forbidden\r\n\r\n')); await listen(deny, 19446);
    const requireWeb = createRequire(join(options.web, 'package.json')); const { chromium } = requireWeb('@playwright/test');
    const executablePath = '/usr/bin/chromium';
    browserProbe = await chromium.launchPersistentContext(join(work, 'untrusted-profile'), { executablePath, headless: true, ignoreHTTPSErrors: false, proxy: { server: 'http://127.0.0.1:19446', bypass: 'localhost,127.0.0.1' }, env: childEnv });
    try {
      const page = await browserProbe.newPage(); let rejected = false;
      try { await page.goto(origins.web); } catch (error) { rejected = /ERR_CERT_AUTHORITY_INVALID/.test(error.message); }
      if (!rejected) fail('UNTRUSTED_CA_NOT_REJECTED'); metadata.checks.untrustedCaRejected = true;
    } finally { await browserProbe.close(); browserProbe = undefined; }
    await mkdir(staging); await mkdir(join(work, 'profiles'));
    await symlink(join(options.web, 'node_modules'), join(staging, 'node_modules'), 'dir');
    const suiteFile = options.suite === 'new-managed8' ? 'managed-browser.spec.ts' : options.suite === 'original-host8' ? 'host-creation.spec.ts' : 'auth-recovery.spec.ts';
    const suitePath = options.suite === 'new-managed8' ? join(here, suiteFile) : join(options.web, 'integration/host-creation', suiteFile);
    const fixturePath = join(options.web, 'integration/host-creation/fixtures.ts');
    await copyFile(suitePath, join(staging, suiteFile)); await copyFile(fixturePath, join(staging, 'original-fixtures.ts')); await copyFile(join(here, 'observed-fixtures.ts'), join(staging, 'fixtures.ts')); await copyFile(join(here, 'browser-observation.mjs'), join(staging, 'browser-observation.mjs')); await copyFile(join(here, 'playwright.managed.config.mjs'), join(staging, 'playwright.managed.config.mjs'));
    for (const [from, to] of [[suitePath, join(staging, suiteFile)], [fixturePath, join(staging, 'original-fixtures.ts')]]) if (digest(await readFile(from)) !== digest(await readFile(to))) fail('STAGING_CHANGED_INPUT');
    metadata.stagedInputs = { suiteSha256: digest(await readFile(suitePath)), fixtureSha256: digest(await readFile(fixturePath)) };
    const observationDirectory = join(work, 'browser-observations'); await mkdir(observationDirectory);
    const stagedBefore = await hashes(staging, await files(staging, '', ['node_modules']));
    metadata.stagedExecutionSha256Before = stagedBefore;
    const report = join(work, 'playwright.json');
    const testEnv = { ...childEnv, MANAGED_BROWSER_OBSERVATION_DIR: observationDirectory, HOST_INTEGRATION_BROWSER_EXECUTABLE: executablePath, HOST_INTEGRATION_CA: join(work, 'ca.pem'), HOST_INTEGRATION_PROFILES_DIR: join(work, 'profiles'), HOST_INTEGRATION_WEB_ORIGIN: origins.web, HOST_INTEGRATION_API_ORIGIN: origins.api, HOST_INTEGRATION_CONTROL_ORIGIN: origins.control, HOST_INTEGRATION_OIDC_ORIGIN: health.oidc_origin, HOST_INTEGRATION_DB_CONTAINER: health.db_container, HOST_INTEGRATION_DB_USER: health.db_user, HOST_INTEGRATION_DB_NAME: health.db_name, MANAGED_BROWSER_SPEC: suiteFile, HOST_INTEGRATION_REPORT: report, HOST_INTEGRATION_OUTPUT: join(work, 'test-results') };
    const log = openSync(join(work, 'browser.log'), 'w'); let status;
    try {
      status = await new Promise((done, reject) => {
        activeTests = spawn('node', [join(options.web, 'node_modules/@playwright/test/cli.js'), 'test', '--config', join(staging, 'playwright.managed.config.mjs'), '--workers', '1', '--retries', '0'], { cwd: staging, env: testEnv, detached: true, stdio: ['ignore', log, log] });
        activeTests.once('error', reject); activeTests.once('close', done);
      });
    } finally { activeTests = undefined; closeSync(log); }
    metadata.browserExitCode = status;
    const results = await jsonFile(report); metadata.browserStats = results.stats;
    const cases = [];
    function collect(suites) { for (const group of suites) { for (const spec of group.specs ?? []) for (const test of spec.tests ?? []) { cases.push({ scenario: spec.title.match(/^M0[1-4]\b/)?.[0] ?? 'ORIGINAL_SCOPE', project: test.projectName, status: test.results.length === 1 ? test.results[0].status : 'multiple_attempts', attempts: test.results.length }); } collect(group.suites ?? []); } }
    collect(results.suites ?? []); metadata.browserCases = cases;
    const state = await getJson(`${origins.control}/state`); if (!state) fail('MISSING_MANAGED_LEDGER_STATE'); await writeFile(join(work, 'managed-state.json'), JSON.stringify(state) + '\n');
    const proofFiles = (await files(observationDirectory)).filter(path => /^observer-[0-9]+\.json$/.test(path));
    if (!proofFiles.length) fail('BROWSER_OBSERVATION_MISSING');
    const browserObservation = { routesIntercepted: 0, unexpectedOrigins: 0, contextsObserved: 0, requestsObserved: 0 };
    for (const path of proofFiles) {
      const counters = await jsonFile(join(observationDirectory, path));
      if (!counters || Object.keys(counters).length !== 4 || !['routesIntercepted', 'unexpectedOrigins', 'contextsObserved', 'requestsObserved'].every(key => Number.isSafeInteger(counters[key]) && counters[key] >= 0)) fail('BROWSER_OBSERVATION_INVALID');
      for (const key of Object.keys(browserObservation)) browserObservation[key] += counters[key];
    }
    if (browserObservation.contextsObserved <= 0 || browserObservation.requestsObserved <= 0) fail('BROWSER_OBSERVATION_NOT_ACTIVE');
    metadata.browserObservation = { ...browserObservation, workerProofFiles: proofFiles.length, scope: 'Returned contexts and existing/future pages; original fixture certificate initialization is source-audited before observer attachment.' };
    metadata.stagedExecutionSha256After = await hashes(staging, await files(staging, '', ['node_modules']));
    if (!equal(stagedBefore, metadata.stagedExecutionSha256After) || await realpath(join(staging, 'node_modules')) !== await realpath(join(options.web, 'node_modules'))) fail('STAGED_EXECUTION_CHANGED');
    observations = { ...observations, provenanceAfter: await provenance(manifest), assetsBefore, assetsAfter: await hashes(dist, await files(dist)), browser: { cases, routesIntercepted: browserObservation.routesIntercepted, unexpectedOrigins: browserObservation.unexpectedOrigins }, ledger: state.ledger, network: state.network };
    metadata.managedStateSha256 = digest(await readFile(join(work, 'managed-state.json')));
    metadata.checks.provenanceStable = equal(observations.provenanceBefore, observations.provenanceAfter);
    metadata.checks.assetsStable = equal(observations.assetsBefore, observations.assetsAfter);
    metadata.toolSha256After = await toolHashes(); metadata.checks.toolsStable = equal(metadata.toolSha256Before, metadata.toolSha256After);
    metadata.checks.manifestStable = digest(await readFile(options.manifest)) === manifestHash;
    await verifiedBackend(readinessPath);
    if (!metadata.checks.provenanceStable || !metadata.checks.assetsStable || !metadata.checks.toolsStable || !metadata.checks.manifestStable) fail('INPUTS_CHANGED_DURING_RUN');
    const count = options.suite === 'original-recovery6' ? 6 : 8;
    metadata.suitePassed = status === 0 && results.stats.expected === count && results.stats.unexpected === 0 && results.stats.flaky === 0 && results.stats.skipped === 0 && cases.length === count && cases.every(entry => entry.attempts === 1 && entry.status === 'passed');
    if (!metadata.suitePassed) fail('BROWSER_ASSERTION_OR_SETUP_FAILURE');
  } catch (error) {
    metadata.failureCode = typeof error.code === 'string' ? error.code : 'MANAGED_BROWSER_SETUP_OR_EXECUTION_FAILURE'; process.exitCode = 1;
  } finally {
    try {
      await cleanup();
      // Bind the complete lifetime, including teardown, rather than only the test interval.
      if (pins && inputBefore) {
        if (digest(await readFile(options.manifest)) !== metadata.manifestSha256) fail('MANIFEST_CHANGED_DURING_LIFETIME');
        const finalManifest = await jsonFile(options.manifest);
        const finalProvenance = await provenance(finalManifest);
        const finalTools = await toolHashes();
        const finalAssets = await hashes(dist, await files(dist));
        metadata.provenanceAfterCleanup = finalProvenance;
        metadata.toolSha256AfterCleanup = finalTools;
        metadata.assetsAfterCleanup = finalAssets;
        if (!equal(inputBefore, finalProvenance) || !equal(metadata.toolSha256Before, finalTools) || !equal(assetsBefore, finalAssets)) fail('INPUTS_CHANGED_DURING_LIFETIME');
        await verifiedBackend();
        if (observations) observations = { ...observations, provenanceAfter: finalProvenance, assetsAfter: finalAssets };
        metadata.checks.completeLifetimeInputsStable = true;
      }
      if (observations) {
        observations.cleanup = metadata.cleanup;
        if (metadata.suitePassed && options.suite === 'new-managed8') { validateManagedCompletion(pins, observations); metadata.newManagedMatrixVerified = true; }
        metadata.normalizedObserved = observations;
      }
      if (metadata.cleanup && Object.values(metadata.cleanup).some(value => value !== 0)) fail('CLEANUP_INCOMPLETE');
      metadata.completed = metadata.suitePassed === true && !metadata.failureCode;
    } catch (error) {
      const completionFailure = typeof error.code === 'string' ? error.code : 'CLEANUP_OR_COMPLETION_FAILURE';
      if (metadata.failureCode) metadata.cleanupOrCompletionFailureCode = completionFailure;
      else metadata.failureCode = completionFailure;
      metadata.completed = false; process.exitCode = 1;
    }
    await writeFile(join(work, 'evidence.json'), JSON.stringify(metadata, null, 2) + '\n');
    console.log(JSON.stringify({ scope: options.suite, completed: metadata.completed, failureCode: metadata.failureCode ?? null, evidence: join(work, 'evidence.json') }));
  }
}
await main().catch(error => {
  console.error(JSON.stringify({ completed: false, failureCode: typeof error.code === 'string' ? error.code : 'MANAGED_BROWSER_CONFIGURATION_FAILURE' }));
  process.exitCode = 1;
});
