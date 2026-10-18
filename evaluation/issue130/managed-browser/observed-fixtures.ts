import { test as originalTest, expect } from './original-fixtures';
import type { BrowserContext } from '@playwright/test';
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
// This module only observes public SDK calls; original fixture bytes are staged separately.
// @ts-expect-error The pinned runtime-only observer intentionally has no generated declarations.
import { createBrowserObservation } from './browser-observation.mjs';
const output = process.env.MANAGED_BROWSER_OBSERVATION_DIR;
if (!output) throw new Error('BROWSER_OBSERVATION_DIRECTORY_REQUIRED');
const observer = createBrowserObservation([
  process.env.HOST_INTEGRATION_WEB_ORIGIN,
  process.env.HOST_INTEGRATION_API_ORIGIN,
  'https://localhost:19445',
  process.env.HOST_INTEGRATION_OIDC_ORIGIN,
]);
let contextsObserved = 0;
let requestsObserved = 0;
const wrapped = new WeakSet<object>();
function observeContext(context: BrowserContext) {
  if (!wrapped.has(context)) {
    wrapped.add(context);
    contextsObserved = Math.min(Number.MAX_SAFE_INTEGER, contextsObserved + 1);
    context.on('request', () => { requestsObserved = Math.min(Number.MAX_SAFE_INTEGER, requestsObserved + 1); });
  }
  return observer.wrapContext(context);
}
function flush() {
  writeFileSync(join(output!, `observer-${process.pid}.json`), `${JSON.stringify({ ...observer.snapshot(), contextsObserved, requestsObserved })}\n`, { mode: 0o600 });
}
process.once('exit', flush);
export const test = originalTest.extend({
  newTrustedContext: async ({ newTrustedContext }, use) => {
    try { await use(async () => observeContext(await newTrustedContext())); }
    finally { flush(); }
  },
  newUntrustedContext: async ({ newUntrustedContext }, use) => {
    try { await use(async () => observeContext(await newUntrustedContext())); }
    finally { flush(); }
  },
});
test.afterEach(flush);
export { expect };
