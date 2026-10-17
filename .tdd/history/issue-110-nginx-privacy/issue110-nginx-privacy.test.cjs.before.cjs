const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

// Independent source contract only. Runtime Nginx error-path experiments are separate evidence.
// No browser, Docker, process, network, TLS-key generation or production access occurs here.
const root = path.resolve(__dirname, '../..');
const configuration = fs.readFileSync(path.join(root, 'deploy/nginx/nginx.conf'), 'utf8');

// Read Nginx directive/block boundaries while preserving quoted strings and ignoring comments.
// This is deliberately a small source-contract parser, not an alternative Nginx configuration validator.
function parse(source) {
  const tokens = source.match(/"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|#[^\n]*|[{};]|[^\s{};]+/g) ?? [];
  const result = { header: [], directives: [], children: [] }, stack = [result];
  let words = [];
  for (const token of tokens) {
    if (token.startsWith('#')) continue;
    if (token === '{') {
      assert.ok(words.length, 'Every Nginx block needs a header');
      const child = { header: words, directives: [], children: [] };
      stack.at(-1).children.push(child); stack.push(child); words = [];
    } else if (token === ';') {
      assert.ok(words.length, 'Nginx directive must not be empty');
      stack.at(-1).directives.push(words); words = [];
    } else if (token === '}') {
      assert.equal(words.length, 0, 'Nginx directive must end before a block closes');
      assert.ok(stack.length > 1, 'Nginx block must have a matching opening brace'); stack.pop();
    } else words.push(token);
  }
  assert.equal(stack.length, 1, 'All Nginx blocks must close');
  assert.equal(words.length, 0, 'All Nginx directives must terminate');
  return result;
}
const tree = parse(configuration);
const unique = (values, description) => {
  assert.equal(values.length, 1, description); return values[0];
};
const http = unique(tree.children.filter(node => node.header.join(' ') === 'http'), 'One HTTP configuration is required');
const directive = (node, name) => node.directives.filter(words => words[0] === name);
const oneDirective = (node, name) => unique(directive(node, name), `One ${name} directive is required`);
const https = unique(http.children.filter(node => node.header[0] === 'server'
  && directive(node, 'listen').some(words => words.slice(1).join(' ') === '8443 ssl')), 'One TLS API server is required');
const location = header => unique(https.children.filter(node => node.header.join(' ') === header), `Required route ${header}`);
const expectedProxy = [
  ['limit_req', 'zone=public_api', 'burst=40', 'nodelay'],
  ['proxy_http_version', '1.1'],
  ['proxy_set_header', 'Host', '$host'],
  ['proxy_set_header', 'X-Real-IP', '$remote_addr'],
  ['proxy_set_header', 'X-Forwarded-For', '$proxy_add_x_forwarded_for'],
  ['proxy_set_header', 'X-Forwarded-Proto', 'https'],
  ['proxy_set_header', 'X-Request-ID', '$request_id'],
  ['proxy_connect_timeout', '5s'],
  ['proxy_read_timeout', '75s'],
  ['proxy_send_timeout', '15s'],
  ['proxy_pass', 'http://meet_me_app'],
];
function assertProxy(node) {
  // Inline directives keep all three routes self-contained and avoid an untracked include dependency.
  assert.deepEqual(directive(node, 'include'), [], 'Sensitive routes must not hide proxy behavior in an unapproved include');
  const actual = node.directives.filter(words => words[0] === 'limit_req' || words[0].startsWith('proxy_'));
  assert.deepEqual(actual.slice().sort((a, b) => a.join(' ').localeCompare(b.join(' '))),
    expectedProxy.slice().sort((a, b) => a.join(' ').localeCompare(b.join(' '))));
  assert.deepEqual(directive(node, 'return'), [], 'OAuth callbacks must reach the auth application');
}

for (const provider of ['google', 'kakao']) {
  test(`110 ${provider} OAuth callback suppresses native request-bearing error logs and retains exact proxy policy`, () => {
    const callback = location(`location = /api/auth/${provider}/callback`);
    assert.deepEqual(oneDirective(callback, 'error_log'), ['error_log', '/dev/null', 'crit']);
    assertProxy(callback);
    const access = directive(callback, 'access_log');
    assert.ok(access.length === 0 || (access.length === 1 && access[0].join(' ') === 'access_log /dev/stdout json'),
      'Callback access logs must inherit or explicitly use the safe summary format');
  });
}

test('110 normal HTTPS API traffic keeps the existing proxy and bounded timeout policy', () => {
  assertProxy(location('location /'));
  assert.deepEqual(oneDirective(http, 'error_log'), ['error_log', '/dev/stderr', 'warn']);
  assert.deepEqual(oneDirective(https, 'server_name'), ['server_name', 'api.meet-me.co.kr']);
});

test('110 Nginx access summaries allow URI path and bounded metadata without query, headers or body', () => {
  const format = unique(directive(http, 'log_format').filter(words => words[1] === 'json'), 'One safe JSON access format is required');
  assert.equal(format[2], 'escape=json');
  const variables = [...new Set(format.join(' ').match(/\$[A-Za-z_][A-Za-z0-9_]*/g) ?? [])].sort();
  assert.deepEqual(variables, ['$time_iso8601', '$request_id', '$request_method', '$uri', '$status',
    '$request_time', '$upstream_response_time'].sort());
  assert.match(format.join(' '), /"uri":"\$uri"/);
  assert.deepEqual(oneDirective(http, 'access_log'), ['access_log', '/dev/stdout', 'json']);
  assert.deepEqual(directive(https, 'access_log'), [], 'TLS server must inherit the safe summary format');
  assert.deepEqual(directive(https, 'error_log'), [], 'Callback-specific policy must not silence all API diagnostics');
});

test('110 committed CI verifies the OAuth privacy source boundary', () => {
  const workflow = fs.readFileSync(path.join(root, '.github/workflows/ci.yml'), 'utf8');
  assert.match(workflow, /node --test[^\r\n]*issue110-nginx-privacy\.test\.cjs/);
});
