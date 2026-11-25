/**
 * End-to-end UI verification + screenshot capture.
 *
 * Drives the real application in a real browser against the real backend: opens the
 * branching example, inspects it, runs it, breaks one branch on purpose, introduces a
 * template typo, and checks the phone layout, capturing the README screenshots on the
 * way. Every capture is preceded by checks, so a screenshot is never taken of a broken
 * state; the script exits non-zero if any check fails.
 *
 * Usage (app running on :5173 with its backend on :8080):
 *   npm ci                                   # once; installs Playwright
 *   node capture-screenshots.mjs             # uses Playwright's bundled Chromium
 *   CHROME_PATH="C:/.../chrome.exe" node capture-screenshots.mjs   # or an installed Chrome
 *
 * The backend should be freshly seeded: the script runs and edits the example workflow.
 */
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const BASE = process.env.FLOWCANVAS_URL ?? 'http://localhost:5173';
const OUT = process.env.SHOT_DIR ?? fileURLToPath(new URL('../docs/screenshots', import.meta.url));
const EXAMPLE = 'Balanced Research Brief';
mkdirSync(OUT, { recursive: true });

const errors = [];
const results = [];

function check(name, condition, detail = '') {
  results.push({ name, ok: !!condition, detail });
  console.log(`${condition ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
}

const browser = await chromium.launch(
  process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {},
);
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 }, deviceScaleFactor: 2 });
page.on('console', (message) => {
  if (message.type() === 'error') {
    errors.push(message.text());
  }
});
page.on('pageerror', (error) => errors.push(`pageerror: ${error.message}`));

const shot = async (name) => {
  await page.mouse.move(0, 0); // no stray hover states in the picture
  await page.waitForTimeout(450);
  await page.screenshot({ path: `${OUT}/${name}.png` });
  console.log(`      captured ${name}.png`);
};
const node = (label) => page.locator('.flow-node', { has: page.locator('.flow-node__label', { hasText: label }) });
const selectNode = async (label) => {
  await node(label).locator('.flow-node__label').click();
  await page.getByTestId('inspector').waitFor();
};
const setTemplate = async (text) => {
  const template = page.getByTestId('inspector-template');
  await template.fill(text);
};
const runAndWait = async () => {
  await page.getByTestId('run-workflow').click();
  await page.locator('.run-panel__status').filter({ hasText: /Succeeded|Failed/ }).waitFor({ timeout: 30000 });
  await page.waitForTimeout(300);
};

await page.goto(BASE, { waitUntil: 'networkidle' });
await page.waitForSelector('.toolbar', { timeout: 15000 });

// --- 1. First-run experience --------------------------------------------------------
await page.locator('.canvas__example').first().waitFor();
check('empty canvas invites the user in', await page.getByTestId('canvas-empty').isVisible());
check('examples offered on the canvas', (await page.locator('.canvas__example').count()) >= 4);
check('parts bin visible', await page.getByTestId('add-node-OUTPUT').isVisible());
await shot('01-empty-state');

// --- 2. Open the branching example as a saved workflow ------------------------------
// Opening the saved copy (rather than the example) means running it does not create
// a duplicate entry in the library.
const saved = await (await page.request.get(`${BASE}/api/v1/workflows?search=${encodeURIComponent(EXAMPLE)}`)).json();
const workflowId = saved.items[0]?.id;
check('example workflow is seeded', !!workflowId);
await page.getByTestId(`workflow-open-${workflowId}`).click();
await page.locator('.flow-node').nth(7).waitFor();
await page.waitForTimeout(900); // fit-view animation
check('fan-out graph has 8 nodes', (await page.locator('.flow-node').count()) === 8);
check('and 8 cables', (await page.locator('.react-flow__edge').count()) === 8);
await shot('02-workflow-loaded');

// --- 3. The join template and the variables in scope --------------------------------
await selectNode('Synthesis Prompt');
const variables = await page.getByTestId('inspector-variables').innerText();
check('join template sees both branches', /advocate/.test(variables) && /critic/.test(variables), variables.replace(/\n/g, ' '));
check('key shown and editable', (await page.getByTestId('inspector-key').inputValue()) === 'synthesisPrompt');
await shot('03-node-inspector');

// --- 4. A successful run, drawn as a trace ------------------------------------------
await page.locator('.canvas .react-flow__pane').click({ position: { x: 40, y: 40 } });
await runAndWait();
const trace = page.locator('.trace__row');
check('trace has a row per node', (await trace.count()) === 8);
check('every node succeeded', (await page.locator('.trace__row--success').count()) === 8);
check('run succeeded', /Succeeded/.test(await page.locator('.run-panel__status').innerText()));
check('final answer shown', /mock:/.test(await page.getByTestId('run-result').innerText()));
await shot('04-execution-results');

// --- 5. Failure isolation: break one branch -----------------------------------------
await selectNode('Case For');
await setTemplate('List the three strongest arguments FOR: {{input}} [[fail]]');
await page.locator('.canvas .react-flow__pane').click({ position: { x: 40, y: 40 } });
await runAndWait();
check('run reports failure', /Failed/.test(await page.locator('.run-panel__status').innerText()));
check('one node failed', (await page.locator('.trace__row--failed').count()) === 1);
check('the other branch still finished', (await node('Critic').getAttribute('data-status')) === 'SUCCESS');
check('downstream nodes skipped', (await page.locator('.trace__row--skipped').count()) === 3);
check('error shown without a click', /asked to fail/.test(await page.getByTestId('run-result').innerText()));
await shot('05-failure-isolation');

// --- 6. Live problems: a template typo ----------------------------------------------
await selectNode('Case For');
await setTemplate('List the three strongest arguments FOR: {{topik}}');
await page.getByRole('tab', { name: /Problems/ }).click();
const problems = await page.getByTestId('validation-panel').innerText();
check('typo reported before running', /topik/.test(problems) && /blocks this workflow/.test(problems));
check('run disabled while broken', await page.getByTestId('run-workflow').isDisabled());
check('offending node outlined', (await node('Case For').getAttribute('class')).includes('has-problem'));
await shot('06-live-problems');

// Put the template back and save, leaving the data as it was found.
await setTemplate('List the three strongest arguments FOR: {{input}}');
await page.getByTestId('save-workflow').click();
await page.getByTestId('toast').waitFor();
await page.getByTestId('toast').waitFor({ state: 'detached', timeout: 10000 });
await page.getByRole('tab', { name: 'Run' }).click();
await page.locator('.canvas .react-flow__pane').click({ position: { x: 40, y: 40 } });
await runAndWait(); // the phone shot shows a healthy run, not the deliberately broken one
check('repaired workflow runs again', /Succeeded/.test(await page.locator('.run-panel__status').innerText()));

// --- 7. Phone layout ----------------------------------------------------------------
await page.setViewportSize({ width: 430, height: 932 });
await page.waitForTimeout(1000); // let the layout and the canvas re-measure
await page.locator('.react-flow__controls-fitview').click();
await page.waitForTimeout(900);
const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
check('no sideways scrolling on a phone', overflow <= 0, `overflow ${overflow}px`);
await shot('07-responsive-mobile');

// --- Report -------------------------------------------------------------------------
check('no console errors', errors.length === 0, errors.slice(0, 3).join(' | '));
await browser.close();

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} UI checks passed`);
if (failed.length > 0) {
  console.log('FAILURES:', failed.map((f) => f.name).join(', '));
  process.exit(1);
}
