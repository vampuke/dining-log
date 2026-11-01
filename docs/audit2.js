const { chromium } = require('/root/.npm/_npx/e41f203b7505f1fb/node_modules/playwright-core');

function relLum(rgb) {
  const f = c => (c /= 255) <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  return 0.2126 * f(rgb[0]) + 0.7152 * f(rgb[1]) + 0.0722 * f(rgb[2]);
}
const ratio = (a, b) => { const l1 = relLum(a), l2 = relLum(b); return (Math.max(l1,l2)+0.05)/(Math.min(l1,l2)+0.05); };
function parse(c) {
  const m = String(c).match(/rgba?\(([\d.]+),\s*([\d.]+),\s*([\d.]+)(?:,\s*([\d.]+))?/);
  return m ? [+m[1], +m[2], +m[3], m[4] === undefined ? 1 : +m[4]] : null;
}
const blend = (fg, bg) => [0,1,2].map(i => Math.round(fg[i]*fg[3] + bg[i]*(1-fg[3])));

(async () => {
  const browser = await chromium.launch({
    executablePath: '/root/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome',
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  });
  let fails = 0;
  for (const f of process.argv.slice(2)) {
    for (const theme of ['light', 'dark']) {
      const ctx = await browser.newContext({ viewport: { width: 2200, height: 1000 } });
      const page = await ctx.newPage();
      await page.addInitScript(t => { try { localStorage.setItem('shiji-prototype-theme', JSON.stringify(t)); } catch(e){} }, theme);
      await page.goto('file://' + f, { waitUntil: 'domcontentloaded', timeout: 60000 });
      // force the class too, in case the page script keys off a different storage name
      await page.evaluate(t => document.documentElement.classList.toggle('dark', t === 'dark'), theme);
      await page.waitForTimeout(500);
      const d = await page.evaluate(() => {
        const scr = document.getElementById('screen-home');
        const q = (root, sel) => { const el = root && root.querySelector(sel); if (!el) return null;
          const s = getComputedStyle(el); const r = el.getBoundingClientRect();
          return { sel, text: (el.textContent||'').trim().slice(0,20), color: s.color, size: s.fontSize, w: Math.round(r.width), h: Math.round(r.height) }; };
        const form = document.getElementById('screen-form');
        const seg = form && form.querySelector('.rating-option');
        return {
          bg: getComputedStyle(scr).backgroundColor,
          rows: ['.restaurant-name','.restaurant-meta','.restaurant-date','.row-rating','.list-meta',
                 '.nav-item.active','.nav-item:not(.active)','.chip'].map(s => q(scr, s)).filter(Boolean),
          seg: seg ? { h: Math.round(seg.getBoundingClientRect().height), color: getComputedStyle(seg).color } : null,
        };
      });
      const bgc = parse(d.bg).slice(0,3);
      console.log(`\n### ${f.split('/').pop()} [${theme}] bg=${d.bg}`);
      for (const r of d.rows) {
        const c = blend(parse(r.color), bgc);
        const v = ratio(c, bgc);
        const bad = v < 4.5;
        if (bad) fails++;
        console.log(`  ${r.sel.padEnd(26)} ${r.size.padEnd(6)} ${String(r.color).padEnd(22)} ${v.toFixed(2)} ${bad ? '**FAIL**' : 'AA'}  "${r.text}"`);
      }
      console.log('  rating segment height:', d.seg && d.seg.h);
      await ctx.close();
    }
  }
  console.log('\nFAILS =', fails);
  await browser.close();
})();
