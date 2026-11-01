const { chromium } = require('/root/.npm/_npx/e41f203b7505f1fb/node_modules/playwright-core');

(async () => {
  const files = process.argv.slice(2);
  const browser = await chromium.launch({
    executablePath: '/root/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome',
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--font-render-hinting=none'],
  });
  for (const f of files) {
    const base = f.split('/').pop().replace('.html', '');
    for (const theme of ['light', 'dark']) {
      const ctx = await browser.newContext({ viewport: { width: 2200, height: 1000 }, deviceScaleFactor: 1 });
      const page = await ctx.newPage();
      await page.goto('file://' + f, { waitUntil: 'domcontentloaded', timeout: 60000 });
      await page.evaluate((t) => {
        document.documentElement.classList.toggle('dark', t === 'dark');
        localStorage.setItem('shiji-prototype-theme', JSON.stringify(t));
      }, theme);
      await page.waitForTimeout(1500);
      try { await page.evaluate(() => document.fonts.ready.then(()=>true)); } catch(e) {}
      await page.waitForTimeout(500);
      const out = `/root/dining_log/docs/shots/${base}-${theme}.png`;
      await page.screenshot({ path: out, fullPage: true });
      console.log(out);
      // per-screen crops
      const boards = await page.$$('figure.artboard');
      for (let i = 0; i < boards.length; i++) {
        const p = `/root/dining_log/docs/shots/${base}-${theme}-s${i + 1}.png`;
        await boards[i].screenshot({ path: p });
      }
      await ctx.close();
    }
  }
  await browser.close();
})();
