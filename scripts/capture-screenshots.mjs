/**
 * End-to-end UI verification + screenshot capture.
 *
 * Drives the real application in a real browser against the real backend:
 * loads an example, runs the workflow, and captures the screenshots used in the README.
 */
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const BASE = process.env.FLOWCANVAS_URL ?? 'http://localhost:5173';
const OUT = process.env.SHOT_DIR ?? fileURLToPath(new URL('../docs/screenshots', import.meta.url));
mkdirSync(OUT, { recursive: true });

const errors = [];
const results = [];

function check(name, condition, detail = '') {
  results.push({ name, ok: !!condition, detail });
  console.log(`${condition ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
}

const browser = await chromium.launch({ args: ['--no-sandbox', '--disable-dev-shm-usage'] });
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 }, deviceScaleFactor: 2 });

page.on('console', (message) => {
  if (message.type() === 'error') {
    errors.push(message.text());
  }
});
page.on('pageerror', (error) => errors.push(`pageerror: ${error.message}`));

await page.goto(BASE, { waitUntil: 'networkidle' });
await page.waitForSelector('.toolbar', { timeout: 15000 });

// --- 1. Empty / library view ------------------------------------------------------
// The canvas starts empty on purpose, so this is the "first run" experience.
await page.waitForTimeout(700);
await page.screenshot({ path: `${OUT}/01-empty-state.png` });
check('app shell renders', (await page.locator('.toolbar').count()) === 1);
check('library panel present', (await page.locator('.library').count()) === 1);
check('palette present', (await page.locator('.palette').count()) === 1);
check('empty canvas shows no nodes', (await page.locator('.flow-node').count()) === 0);

// --- 2. Load an example workflow ---------------------------------------------------
await page.getByTestId('example-Customer Support Reply').click();
await page.waitForSelector('.flow-node', { timeout: 15000 });
await page.waitForTimeout(900);
const nodeCount = await page.locator('.flow-node').count();
const edgeCount = await page.locator('.react-flow__edge').count();
check('example loads 4 nodes', nodeCount === 4, `got ${nodeCount}`);
check('example loads 3 edges', edgeCount === 3, `got ${edgeCount}`);
await page.screenshot({ path: `${OUT}/02-workflow-loaded.png` });

// --- 3. Select a node to show the inspector ----------------------------------------
await page.locator('.flow-node').nth(1).click();
await page.waitForTimeout(400);
check('inspector opens on select', (await page.getByTestId('inspector').count()) === 1);
await page.screenshot({ path: `${OUT}/03-node-inspector.png` });

// --- 4. Validate --------------------------------------------------------------------
await page.getByTestId('validate-workflow').click();
await page.waitForTimeout(1200);
const validationText = await page.getByTestId('validation-panel').innerText();
check('validation reports the workflow as valid', /Valid/.test(validationText), validationText.replace(/\n/g, ' | '));

// --- 5. Run -------------------------------------------------------------------------
await page.getByTestId('run-workflow').click();
await page.waitForSelector('.run-item', { timeout: 20000 });
await page.waitForTimeout(1500);
const runItems = await page.locator('.run-item').count();
check('run produced results for every node', runItems === 4, `got ${runItems}`);
const runText = await page.getByTestId('run-panel').innerText();
check('run panel shows a SUCCESS status', /SUCCESS/.test(runText));
check('run panel shows durations', /ms/.test(runText));
check('run panel shows node outputs', /mock/.test(runText), 'expected the mock provider output');
await page.screenshot({ path: `${OUT}/04-execution-results.png` });

// --- 6. Delete and re-create a connection ------------------------------------------
// Selecting an edge and pressing Delete removes it; dragging from a source handle to a
// target handle creates one. Verifying both exercises the core editing loop.
await page.locator('.react-flow__edge').last().click({ force: true });
await page.waitForTimeout(300);
await page.keyboard.press('Delete');
await page.waitForTimeout(500);
const afterDelete = await page.locator('.react-flow__edge').count();
check('selecting an edge and deleting removes it', afterDelete === 2, `got ${afterDelete}`);

const sourceHandle = page.locator('.flow-node').nth(2).locator('.react-flow__handle.source');
const targetHandle = page.locator('.flow-node').nth(3).locator('.react-flow__handle.target');
const sourceBox = await sourceHandle.boundingBox();
const targetBox = await targetHandle.boundingBox();
check('source and target handles are on screen', !!sourceBox && !!targetBox);

if (sourceBox && targetBox) {
  await page.mouse.move(sourceBox.x + sourceBox.width / 2, sourceBox.y + sourceBox.height / 2);
  await page.mouse.down();
  // React Flow needs a moment and a few intermediate moves to start a connection.
  await page.waitForTimeout(150);
  await page.mouse.move(sourceBox.x + 40, sourceBox.y, { steps: 5 });
  await page.waitForTimeout(120);
  await page.mouse.move(targetBox.x + targetBox.width / 2, targetBox.y + targetBox.height / 2, { steps: 20 });
  await page.waitForTimeout(200);
  await page.mouse.up();
  await page.waitForTimeout(700);
}
const afterConnect = await page.locator('.react-flow__edge').count();
check('dragging between handles creates a connection', afterConnect === 3, `got ${afterConnect}`);
await page.screenshot({ path: `${OUT}/05-canvas-editing.png` });

// --- 7. Responsive view -------------------------------------------------------------
await page.setViewportSize({ width: 430, height: 900 });
await page.waitForTimeout(700);
await page.screenshot({ path: `${OUT}/06-responsive-mobile.png`, fullPage: false });
check('mobile layout renders', (await page.locator('.toolbar').count()) === 1);

// --- Report -------------------------------------------------------------------------
check('no console errors', errors.length === 0, errors.slice(0, 3).join(' | '));

await browser.close();

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} UI checks passed`);
if (failed.length > 0) {
  console.log('FAILURES:', failed.map((f) => f.name).join(', '));
  process.exit(1);
}
