// Run only through check-neon-connection.py, against its isolated synthetic-data branch.
const {createRequire} = require('node:module');
const crypto = require('node:crypto');
const requireFrontend = createRequire(require('node:path').resolve(__dirname, '../frontend/package.json'));
const {chromium, expect} = requireFrontend('@playwright/test');
(async () => {
  const browser = await chromium.launch({headless:true});
  const context = await browser.newContext();
  const page = await context.newPage();
  const origin = 'http://localhost:3003';
  const email = `neon-${crypto.randomUUID()}@example.invalid`;
  const password = crypto.randomBytes(24).toString('base64url') + 'A1';
  try {
    await page.goto(origin+'/signup');
    await page.getByLabel('이메일',{exact:true}).fill(email);
    await page.getByLabel('비밀번호',{exact:true}).fill(password);
    await page.getByLabel('비밀번호 확인',{exact:true}).fill(password);
    await page.getByRole('checkbox').check();
    await page.getByRole('button',{name:'회원가입',exact:true}).click();
    await expect(page.getByText('가입되었습니다. 로그인해 첫 거래를 기록하세요.')).toBeVisible({timeout:30000});
    console.log('PASS real Neon signup through Next.js and Spring Boot');
    await page.getByRole('link',{name:'로그인',exact:true}).click();
    await page.getByLabel('이메일',{exact:true}).fill(email);
    await page.getByLabel('비밀번호',{exact:true}).fill(password);
    await page.getByRole('button',{name:'로그인',exact:true}).click();
    await expect(page.getByRole('link',{name:'＋ 거래 기록',exact:true})).toBeVisible({timeout:30000});
    console.log('PASS real Neon login/session');
    await page.getByRole('link',{name:'＋ 거래 기록',exact:true}).click();
    await page.getByLabel('금액 · 원').fill('12000');
    const categories=page.getByLabel('카테고리',{exact:true});
    await expect.poll(()=>categories.locator('option').count()).toBeGreaterThan(1);
    const option=await categories.locator('option').evaluateAll(options=>options.find(x=>x.value)?.value);
    await categories.selectOption(option);
    await page.getByRole('button',{name:'저장',exact:true}).click();
    await expect(page.getByRole('heading',{name:'거래 상세',exact:true})).toBeVisible({timeout:30000});
    await expect(page.getByText('12,000원',{exact:true})).toBeVisible();
    const detail=page.url();
    await page.reload();
    await expect(page.getByText('12,000원',{exact:true})).toBeVisible({timeout:30000});
    if(page.url()!==detail) throw Error('Detail route did not persist');
    console.log('PASS real Neon transaction write and persisted reload');
    const summary=await page.evaluate(async()=>{
      const me=await fetch('/api/v1/users/me').then(r=>r.json());
      const today=me.data.today;
      const response=await fetch(`/api/v1/statistics/summary?from=${today}&to=${today}`);
      const data=await response.json();
      return response.status===200 && data.data.expenseTotal==='12000' && data.data.expenseCount===1;
    });
    if(!summary) throw Error('Neon persisted statistics mismatch');
    console.log('PASS real Neon statistics from persisted transaction');
    const removed=await page.evaluate(async password=>{
      await fetch('/api/v1/auth/csrf');
      const token=document.cookie.split('; ').find(x=>x.startsWith('XSRF-TOKEN='))?.slice(11);
      const response=await fetch('/api/v1/users/me',{method:'DELETE',headers:{'Content-Type':'application/json','X-XSRF-TOKEN':decodeURIComponent(token||'')},body:JSON.stringify({currentPassword:password,confirmation:'DELETE'})});
      const denied=await fetch('/api/v1/users/me');
      return response.status===204 && denied.status===401;
    },password);
    if(!removed) throw Error('Synthetic account cleanup or session revocation failed');
    console.log('PASS synthetic account deletion and revoked session');
  } finally { await context.close(); await browser.close(); }
})().catch(()=>{ console.error('FAIL real Neon browser verification; no private values printed'); process.exitCode=1; });
