// Attestation consistency checks; actual HTTP/SQL/runtime evidence is collected separately.
const isObject = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const integer = value => Number.isSafeInteger(value);
const positive = value => integer(value) && value > 0;
const zero = value => integer(value) && value === 0;
const sameKeys = (left, right) => {
  const a = Object.keys(left).sort();
  const b = Object.keys(right).sort();
  return a.length === b.length && a.every((key, index) => key === b[index]);
};
const sameMap = (left, right) => isObject(left) && isObject(right)
  && sameKeys(left, right) && Object.keys(left).every(key => left[key] === right[key]);
const fileMap = value => isObject(value) && Object.keys(value).length > 0
  && Object.entries(value).every(([path, hash]) => path.length > 0 && typeof hash === 'string' && /^[a-f0-9]{64}$/.test(hash));
const graphKeys = ['provider', 'classifier', 'budget', 'repository', 'gate'];
const graph = value => isObject(value) && sameKeys(value, Object.fromEntries(graphKeys.map(key => [key, true])))
  && graphKeys.every(key => typeof value[key] === 'string' && value[key].trim().length > 0);
const exactList = (value, expected) => Array.isArray(value) && value.length === expected.length
  && new Set(value).size === value.length && value.every(item => expected.includes(item));
function requireEvidence(condition, code) {
  if (!condition) {
    const error = new Error(code);
    error.code = code;
    throw error;
  }
}
function validatePins(pins) {
  requireEvidence(isObject(pins) && pins.schemaVersion === 1
    && isObject(pins.web) && /^[a-f0-9]{40}$/.test(pins.web.head ?? '') && fileMap(pins.web.files)
    && isObject(pins.server) && /^[a-f0-9]{40}$/.test(pins.server.head ?? '') && fileMap(pins.server.files)
    && isObject(pins.runtime) && fileMap(pins.runtime.files) && graph(pins.graph)
    && exactList(pins.scenarios, ['M01', 'M02', 'M03', 'M04'])
    && exactList(pins.projects, ['desktop', 'mobile']), 'INVALID_PINS');
}
function validateProvenance(pins, evidence) {
  requireEvidence(isObject(evidence) && evidence.webHead === pins.web.head
    && evidence.serverHead === pins.server.head
    && fileMap(evidence.webFiles) && sameMap(pins.web.files, evidence.webFiles)
    && fileMap(evidence.serverFiles) && sameMap(pins.server.files, evidence.serverFiles), 'SOURCE_PIN_MISMATCH');
  requireEvidence(fileMap(evidence.runtimeFiles)
    && sameMap(pins.runtime.files, evidence.runtimeFiles), 'RUNTIME_PIN_MISMATCH');
}
export function validateManagedReadiness(pins, observed) {
  validatePins(pins);
  validateProvenance(pins, observed?.provenanceBefore);
  const health = observed?.health;
  requireEvidence(isObject(health) && health.fixture === 'issue130-managed-browser-v1'
    && health.production === false && health.primaryProviderFake === false
    && health.managedEnabled === true && health.providerEnabled === true
    && health.routingEnabled === true && health.classifierEnabled === true
    && graph(health.graph) && sameMap(pins.graph, health.graph), 'MANAGED_GRAPH_REQUIRED');
  const outbound = observed?.outbound;
  requireEvidence(isObject(outbound) && outbound.mode === 'LOOPBACK_DENY_ALL_OTHER'
    && outbound.installedBeforeApplication === true && outbound.negativeProbeDenied === true
    && zero(outbound.nonLoopbackConnections), 'OUTBOUND_DENY_REQUIRED');
  const credentials = observed?.credentials;
  requireEvidence(isObject(credentials) && credentials.syntheticOnly === true
    && credentials.realEnvironmentKeysInherited === false, 'SYNTHETIC_CREDENTIALS_REQUIRED');
  return { ready: true };
}
export function validateManagedCompletion(pins, observed) {
  validateManagedReadiness(pins, observed);
  validateProvenance(pins, observed?.provenanceAfter);
  requireEvidence(fileMap(observed.assetsBefore) && fileMap(observed.assetsAfter)
    && sameMap(observed.assetsBefore, observed.assetsAfter), 'ASSET_PIN_MISMATCH');
  const browser = observed.browser;
  const expected = new Set(pins.scenarios.flatMap(scenario => pins.projects.map(project => `${scenario}:${project}`)));
  requireEvidence(isObject(browser) && Array.isArray(browser.cases) && browser.cases.length === expected.size
    && zero(browser.routesIntercepted) && zero(browser.unexpectedOrigins), 'EXACT_BROWSER_MATRIX_REQUIRED');
  const seen = new Set();
  for (const entry of browser.cases) {
    requireEvidence(isObject(entry) && typeof entry.scenario === 'string' && typeof entry.project === 'string'
      && entry.status === 'passed' && integer(entry.attempts) && entry.attempts === 1, 'EXACT_BROWSER_MATRIX_REQUIRED');
    const key = `${entry.scenario}:${entry.project}`;
    requireEvidence(expected.has(key) && !seen.has(key), 'EXACT_BROWSER_MATRIX_REQUIRED');
    seen.add(key);
  }
  const ledger = observed.ledger;
  requireEvidence(isObject(ledger) && positive(ledger.reservations) && positive(ledger.dispatchClaims)
    && positive(ledger.observations) && isObject(ledger.operationCounts)
    && ['PRECHECK_CLASSIFICATION', 'PRECHECK_COUNT', 'PRECHECK_GENERATION'].every(key => positive(ledger.operationCounts[key]))
    && zero(ledger.missingOrMismatchedDispatches), 'REAL_MANAGED_LEDGER_REQUIRED');
  const network = observed.network;
  requireEvidence(isObject(network) && zero(network.remoteCalls)
    && positive(network.localSyntheticCalls), 'EXTERNAL_PROVIDER_FORBIDDEN');
  const cleanup = observed.cleanup;
  requireEvidence(isObject(cleanup) && ['ownedContainersRemaining', 'ownedListenersRemaining', 'privateTempMaterialRemaining']
    .every(key => zero(cleanup[key])), 'CLEANUP_INCOMPLETE');
  return { verified: true };
}
