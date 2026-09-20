// Usage: node browse.mjs <url> [--text | --html | --shot [file.png]] [--wait ms]
// Drives the native chromium running on CDP 127.0.0.1:9222 (start it with chromium-headless.sh).
Object.defineProperty(process, 'platform', { value: 'linux' }); // Termux reports 'android'
const { chromium } = await import('playwright-core');
const a = process.argv.slice(2);
const url = a.find(x => !x.startsWith('--'));
if (!url) { console.error('need a URL'); process.exit(1); }
const mode = a.includes('--html') ? 'html' : a.includes('--text') ? 'text' : a.includes('--shot') ? 'shot' : 'title';
const shotFile = mode === 'shot' ? (a[a.indexOf('--shot')+1] && !a[a.indexOf('--shot')+1].startsWith('--') ? a[a.indexOf('--shot')+1] : 'shot.png') : null;
const waitMs = a.includes('--wait') ? Number(a[a.indexOf('--wait')+1]) : 0;
const b = await chromium.connectOverCDP('http://127.0.0.1:9222');
try {
  const ctx = b.contexts()[0] || await b.newContext();
  const page = await ctx.newPage();
  await page.goto(url, { waitUntil: 'networkidle', timeout: 45000 }).catch(() =>
    page.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 }));
  if (waitMs) await page.waitForTimeout(waitMs);
  if (mode === 'html') process.stdout.write(await page.content());
  else if (mode === 'text') process.stdout.write(await page.evaluate(() => document.body.innerText));
  else if (mode === 'shot') { await page.screenshot({ path: shotFile, fullPage: true }); console.error('wrote ' + shotFile); }
  else console.log(await page.title());
  await page.close();
} finally { await b.close(); }
