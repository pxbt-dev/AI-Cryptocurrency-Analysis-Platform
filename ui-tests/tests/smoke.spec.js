// @ts-check
// Smoke tests against a deployed site. Read-only: they only load pages and make GET requests.
const { test, expect } = require('@playwright/test');

const SYMBOLS = ['BTC', 'SOL', 'TAO', 'WIF'];

test.describe('dashboard', () => {
  test('loads with a chart and price panel per symbol and no script errors', async ({ page }) => {
    const pageErrors = [];
    page.on('pageerror', (err) => pageErrors.push(err.message));

    const response = await page.goto('/');
    expect(response?.status()).toBe(200);
    await expect(page).toHaveTitle('AI Cryptocurrency Analysis Platform');

    for (const symbol of SYMBOLS) {
      const s = symbol.toLowerCase();
      await expect(page.locator(`#${s}Chart`)).toBeVisible();
      await expect(page.locator(`#${s}Price`)).toBeAttached();
    }

    // Give startup scripts (chart init, websocket, first data loads) a moment to run
    await page.waitForTimeout(3_000);
    expect(pageErrors, 'uncaught JavaScript errors on the page').toEqual([]);
  });

  test('draws historical candles on the BTC chart', async ({ page }) => {
    const history = page.waitForResponse(
      (r) => r.url().includes('/api/historical/BTC') && r.url().includes('timeframe=1d'),
    );
    await page.goto('/');

    const res = await history;
    expect(res.status()).toBe(200);
    const candles = await res.json();
    expect(Array.isArray(candles) && candles.length).toBeGreaterThan(0);
    for (const field of ['timestamp', 'open', 'high', 'low', 'close']) {
      expect(candles[0], `candle field ${field}`).toHaveProperty(field);
    }

    // The page keeps its Chart.js instances in a top-level `charts` object
    await expect
      .poll(() => page.evaluate(() => charts.BTC?.data?.datasets?.[0]?.data?.length ?? 0))
      .toBeGreaterThan(0);
  });

  test('switching the BTC time scale loads weekly candles', async ({ page }) => {
    await page.goto('/');
    const weekly = page.waitForResponse(
      (r) => r.url().includes('/api/historical/BTC') && r.url().includes('timeframe=1w'),
    );
    await page.locator('select.time-scale-selector').first().selectOption('1w');
    const res = await weekly;
    expect(res.status()).toBe(200);
  });
});

test.describe('deployed API', () => {
  test('health check is UP', async ({ request }) => {
    const res = await request.get('/actuator/health');
    expect(res.status()).toBe(200);
    expect((await res.json()).status).toBe('UP');
  });

  test('chart data endpoint returns prices and predictions', async ({ request }) => {
    const res = await request.get('/api/chart/data?symbol=BTC&timeframe=1d');
    expect(res.status()).toBe(200);
    const body = await res.json();
    expect(body.prices.length).toBeGreaterThan(0);
    expect(body.analysis.timeframePredictions).toHaveProperty('1day');
  });

  test('operator endpoints, actuator env and API docs are not public', async ({ request }) => {
    for (const path of ['/api/debug/jvm', '/actuator/env', '/v3/api-docs', '/swagger-ui/index.html']) {
      const res = await request.get(path, { maxRedirects: 0 });
      expect([401, 404], `${path} returned ${res.status()}`).toContain(res.status());
    }
  });
});
