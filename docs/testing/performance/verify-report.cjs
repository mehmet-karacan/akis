/* Run from the repository: node docs/testing/performance/verify-report.cjs
 * Synthetic fixtures exist only in memory and in the OS temporary directory.
 * They are never written into the delivered report template.
 */
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require(require.resolve('playwright', { paths: [path.resolve(__dirname, '../../../frontend')] }));

(async () => {
  const temp = await fs.mkdtemp(path.join(os.tmpdir(), 'sky-dit-report-check-'));
  const reportPath = path.join(__dirname, 'rapor.html');
  const browser = await chromium.launch({ headless: true, channel: 'msedge' });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1080 }, deviceScaleFactor: 1 });
  const errors = [], requests = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('request', req => { if (/^https?:/.test(req.url())) requests.push(req.url()); });
  const check = async (name, fn) => { await fn(); console.log('PASS ' + name); };
  async function load(d) {
    await page.locator('#data-file').setInputFiles({ name: 'synthetic-verification.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(d)) });
    await page.waitForFunction(() => document.getElementById('data-file').value==='');
    await page.waitForFunction(() => document.getElementById('message').textContent.includes('yüklendi') || document.getElementById('message').classList.contains('error'));
  }
  try {
    await page.goto(pathToFileURL(reportPath).href);
    await check('SKY - DIT branding, product name, and default HTML filename are visible offline', async () => {
      assert.equal(await page.locator('.wordmark').innerText(), 'SKY - DIT');
      assert.equal(await page.locator('.brand-note').innerText(), 'Data Integration Tool');
      assert.match(await page.title(), /SKY - DIT · Data Integration Tool/);
      assert.match(await page.locator('.report-footer').innerText(), /SKY - DIT · Data Integration Tool/);
      assert.doesNotMatch(await page.locator('body').innerText(), /\bAKIS\b/);
      const [download] = await Promise.all([page.waitForEvent('download'), page.locator('#download-html').click()]);
      assert.equal(download.suggestedFilename(), 'sky-dit-performans.html');
    });
    await check('Blank report contains six planned stages, no fake results, and six timeline placeholders', async () => {
      assert.equal(await page.locator('.stage').count(), 6);
      assert.equal(await page.locator('#stage-table tr').count(), 6);
      assert.equal(await page.locator('#kpi-runs').innerText(), '0 / 18');
      assert.equal(await page.locator('#kpi-throughput').innerText(), '—');
      assert.equal(await page.locator('svg').count(), 0);
      assert.equal(await page.locator('.timeline-grid .chart-empty').count(), 6);
      assert.equal(await page.locator('#download-csv').isDisabled(), true);
      assert.match(await page.locator('#collection-state').innerText(), /Hazır/);
      assert.match(await page.locator('#coverage-summary').innerText(), /Kaynak örneği yok/);
    });
    let template;
    await check('JSON template download includes metadata and empty measurements', async () => {
      const [download] = await Promise.all([page.waitForEvent('download'), page.locator('#download-template').click()]);
      assert.equal(download.suggestedFilename(), 'sky-dit-performans-veri-sablonu.json');
      const file = path.join(temp, 'template.json'); await download.saveAs(file);
      template = JSON.parse(await fs.readFile(file, 'utf8'));
      assert.deepEqual(template.runs, []); assert.deepEqual(template.findings, []);
      assert.deepEqual(template.diagnostics, []);
      assert.equal(template.schemaVersion, 1); assert.equal(template.runTemplate.durationSeconds, null);
      assert.ok(Object.hasOwn(template.environment.client, 'cpu'));
      assert.equal(template.method.sampleIntervalSeconds, 1);
      assert.match(template.report.title, /SKY - DIT/);
      assert.deepEqual(template.collection, {state:'ready',notes:[],sources:{}});
    });
    await check('Downloaded blank JSON can be imported without inventing observations', async () => {
      await load(template); assert.equal(await page.locator('#message.error').count(), 0);
      assert.equal(await page.locator('#kpi-failed').innerText(), '—');
    });
    const ready = structuredClone(template);
    ready.collection = {state:'ready',notes:['Synthetic inventory only; remote Oracle metrics unavailable.'],sources:{client:'Synthetic OS inventory',oracle:{state:'unavailable',reason:'Synthetic fixture; no DB access'},postgresql:null}};
    ready.environment.client.cpu = 'Synthetic inventory CPU';
    ready.environment.client.logicalCores = 8;
    ready.environment.client.memoryGiB = 16;
    ready.method.processScope = 'Synthetic PID 123; workers excluded; normalized to total host CPU capacity.';
    await check('Injected ready report shows inventory and collection notes without inventing runs or metrics', async () => {
      const source = await fs.readFile(reportPath, 'utf8');
      const json = JSON.stringify(ready).replace(/</g, '\\u003c');
      const file = path.join(temp, 'injected-ready.html');
      await fs.writeFile(file, source.replace(/(<script id="report-data" type="application\/json">)[\s\S]*?(<\/script>)/, (_,open,close) => open+'\n'+json+'\n'+close));
      await page.goto(pathToFileURL(file).href);
      assert.match(await page.locator('#env-grid').innerText(), /Synthetic inventory CPU/);
      assert.match(await page.locator('#collection-notes').innerText(), /remote Oracle metrics unavailable/);
      assert.match(await page.locator('#collection-sources').innerText(), /Synthetic OS inventory/);
      assert.match(await page.locator('#collection-summary').innerText(), /Henüz çalıştırma veya kaynak örneği yok/);
      assert.match(await page.locator('#message').innerText(), /Hazır/);
      assert.match(await page.locator('#coverage-scope').innerText(), /Synthetic PID 123/);
      assert.equal(await page.locator('#kpi-runs').innerText(), '0 / 18');
      assert.equal(await page.locator('#kpi-throughput').innerText(), '—');
      assert.equal(await page.locator('svg').count(), 0);
      assert.equal(await page.locator('#download-csv').isDisabled(), true);
      assert.equal(await page.locator('#coverage-metrics dd').count(), 18);
      for (const value of await page.locator('#coverage-metrics dd').allTextContents()) assert.equal(value, 'Ölçülmedi');
      const [download] = await Promise.all([page.waitForEvent('download'), page.locator('#download-template').click()]);
      const blank = path.join(temp, 'fresh-template.json'); await download.saveAs(blank);
      const reset = JSON.parse(await fs.readFile(blank, 'utf8'));
      assert.deepEqual(reset.collection, {state:'ready',notes:[],sources:{}});
      assert.equal(reset.environment.client.cpu, '');
      assert.deepEqual(reset.runs, []);
    });
    await check('All collection states and legacy reports without collection remain supported', async () => {
      for (const [state,label] of [['collecting','Veri toplama sürüyor'],['complete','Veri toplama tamamlandı'],['blocked','Veri toplama engellendi']]) {
        const d=structuredClone(ready); d.collection={state}; await load(d);
        assert.equal(await page.locator('#message.error').count(), 0);
        assert.equal(await page.locator('#collection-state').innerText(), label);
        assert.match(await page.locator('#coverage-summary').innerText(), /Kaynak örneği yok/);
        assert.equal(await page.locator('#kpi-throughput').innerText(), '—');
      }
      const legacy=structuredClone(template); delete legacy.collection; delete legacy.diagnostics; await load(legacy);
      assert.equal(await page.locator('#message.error').count(), 0);
      assert.match(await page.locator('#collection-state').innerText(), /durumu belirtilmedi/);
      assert.equal(await page.locator('#collection-notes li').count(), 0);
    });
    const diagnosticOnly=structuredClone(ready);
    diagnosticOnly.diagnostics=[{id:'host-preflight-synthetic',startedAt:'2026-10-22T10:00:00+03:00',durationSeconds:10,samples:Array.from({length:10},(_,i)=>({tSeconds:i,phase:'baseline',hostCpuPercent:i*10,processCpuPercent:null,hostMemoryUsedMiB:8000+i})),events:[{tSeconds:1,label:'Synthetic preflight only; </script><script>window.untrustedExecuted=true</script>'}]}];
    await check('Diagnostics-only samples show CPU without inventing transfers, throughput, or validation', async () => {
      await load(diagnosticOnly);
      assert.equal(await page.locator('#message.error').count(), 0);
      assert.equal(await page.locator('#run-selector').inputValue(), 'diagnostic:host-preflight-synthetic');
      assert.equal(await page.locator('#run-selector option').count(), 1);
      assert.equal(await page.locator('#run-selector option').innerText(), 'Ölçüm provası (aktarım yapılmadı) · host-preflight-synthetic');
      assert.equal(await page.locator('#cpu-chart svg').count(), 1);
      assert.match(await page.locator('#cpu-chart svg').textContent(), /Ön ölçüm başlangıcı/);
      assert.match(await page.locator('#cpu-chart svg').textContent(), /Ön ölçüm bitişi/);
      assert.match(await page.locator('#cpu-chart svg text').last().getAttribute('text-anchor'), /end/);
      assert.equal(await page.locator('#kpi-runs').innerText(), '0 / 18');
      for (const id of ['kpi-throughput','kpi-validated','kpi-failed']) assert.equal(await page.locator('#'+id).innerText(), '—');
      assert.equal(await page.locator('#run-table tr').count(), 1);
      assert.match(await page.locator('#run-table').innerText(), /Henüz çalıştırma kaydı yok/);
      assert.equal(await page.locator('#duration-chart svg, #throughput-chart svg, #rate-chart svg').count(), 0);
      assert.equal(await page.locator('#download-csv').isDisabled(), true);
      for (const count of await page.locator('#stage-table tr td:nth-child(2)').allTextContents()) assert.equal(count, '0 / 3');
      assert.match(await page.locator('#sample-info').innerText(), /Ölçüm provası \(aktarım yapılmadı\) · 10 örnek/);
      assert.match(await page.locator('#collection-summary').innerText(), /0 aktarım çalıştırması · 10 kaynak örneği \(0 aktarım, 10 ayrı ölçüm provası\)/);
      assert.match(await page.locator('[data-metric="hostCpuPercent"] dd').innerText(), /10 \/ 10 örnek · tam/);
      assert.match(await page.locator('#coverage-summary').innerText(), /2 \/ 18 metrik/);
      assert.match(await page.locator('#events').innerText(), /Synthetic preflight only/);
      assert.equal(await page.evaluate(()=>window.untrustedExecuted), undefined);
      const [download] = await Promise.all([page.waitForEvent('download'), page.locator('#download-template').click()]);
      const file=path.join(temp,'diagnostic-blank-template.json');await download.saveAs(file);
      const reset=JSON.parse(await fs.readFile(file,'utf8'));
      assert.deepEqual(reset.runs, []);assert.deepEqual(reset.diagnostics, []);
    });
    await check('Diagnostics survive offline HTML export and reopening with escaped events', async () => {
      const [download] = await Promise.all([page.waitForEvent('download'), page.locator('#download-html').click()]);
      const file=path.join(temp,'diagnostic-only.html');await download.saveAs(file);
      await page.goto(pathToFileURL(file).href);
      assert.deepEqual(JSON.parse(await page.locator('#report-data').textContent()).diagnostics,diagnosticOnly.diagnostics);
      assert.equal(await page.locator('#cpu-chart svg').count(), 1);
      assert.equal(await page.locator('#kpi-runs').innerText(), '0 / 18');
      assert.equal(await page.locator('#kpi-throughput').innerText(), '—');
      assert.match(await page.locator('#message').innerText(), /Yalnızca ölçüm provası; aktarım yapılmadı/);
      assert.equal(await page.evaluate(()=>window.untrustedExecuted), undefined);
    });
    await check('Malformed diagnostics are rejected without replacing the valid preflight', async () => {
      const mutations=[d=>d.diagnostics=null,d=>d.diagnostics={},d=>d.diagnostics=[null],d=>d.diagnostics.push(structuredClone(d.diagnostics[0])),d=>d.diagnostics[0].id='',d=>d.diagnostics[0].id=123,d=>d.diagnostics[0].startedAt='2026-10-22T10:00:00',d=>d.diagnostics[0].startedAt='invalidZ',d=>delete d.diagnostics[0].startedAt,d=>d.diagnostics[0].durationSeconds=-1,d=>d.diagnostics[0].durationSeconds='10',d=>d.diagnostics[0].durationSeconds=null,d=>delete d.diagnostics[0].samples,d=>d.diagnostics[0].samples={},d=>d.diagnostics[0].samples.reverse(),d=>d.diagnostics[0].samples[1].tSeconds=0,d=>d.diagnostics[0].samples[0].tSeconds='0',d=>d.diagnostics[0].samples[0].phase='preflight',d=>d.diagnostics[0].events=[{tSeconds:'1',label:'bad'}],d=>d.diagnostics[0].events=[{tSeconds:1,label:123}]];
      for(const key of ['hostCpuPercent','processCpuPercent','oracleCpuPercent','postgresCpuPercent'])mutations.push(d=>d.diagnostics[0].samples[0][key]=101);
      for(const key of Object.keys(diagnosticOnly.diagnostics[0].samples[0]).filter(k=>!['tSeconds','phase'].includes(k)))mutations.push(d=>d.diagnostics[0].samples[0][key]=-1);
      for (const mutate of mutations) {
        const bad=structuredClone(diagnosticOnly);mutate(bad);await load(bad);
        assert.equal(await page.locator('#message.error').count(), 1);
        assert.equal(await page.locator('#run-selector').inputValue(), 'diagnostic:host-preflight-synthetic');
        assert.equal(await page.locator('#cpu-chart svg').count(), 1);
        assert.equal(await page.locator('#kpi-runs').innerText(), '0 / 18');
      }
      await load(diagnosticOnly);
    });
    const fixture = structuredClone(template);
    fixture.report.id = 'SYNTHETIC-VERIFICATION';
    fixture.report.notes = '</script><script>window.untrustedExecuted=true</script>';
    fixture.collection = {state:'complete',notes:['Synthetic collection note: </script><script>window.untrustedExecuted=true</script>'],sources:{collector:{name:'Synthetic <img src="https://invalid.example/" onerror="window.untrustedExecuted=true">',scope:'Client only'}}};
    fixture.environment.client.cpu = 'Synthetic CPU; verification only';
    const makeRun = (id, seconds) => ({ ...structuredClone(template.runTemplate), id, status:'completed', purpose:'measurement', targetRows:100000, durationSeconds:seconds, rowsRead:100000, rowsCommitted:100000, failedRows:0, missingRows:0, duplicateRows:0, validationStatus:'passed', startedAt:'2026-10-22T10:00:00+03:00' });
    fixture.runs = [makeRun('test-a', 10), makeRun('test-b', 20), makeRun('test-c', 30), { ...makeRun('warmup', 1), purpose:'warmup' }, { ...makeRun('failed', 5), status:'failed', validationStatus:'failed', rowsCommitted:10, failedRows:2, notes:'=SUM(A1:A2)' }];
    fixture.runs[0].batchLatencyMs = [1, 2, 3, 4, 100];
    fixture.runs[0].events = [{tSeconds: 1, label:'Synthetic background work'}];
    fixture.runs[0].samples = [-1,0,1,2,3,10,11].map((t,i) => ({ tSeconds:t, phase:t<0?'baseline':t>10?'cooldown':'transfer', hostCpuPercent:40+i, processCpuPercent:10+i, hostMemoryUsedMiB:8000+i, hostMemoryAvailableMiB:8000-i, processWorkingSetMiB:500+i, processPrivateMiB:450+i, rowsPerSecond:i===3?null:10000+i, diskReadMiBps:10, diskWriteMiBps:20, networkReceiveMiBps:30, networkSendMiBps:15, oracleCpuPercent:60, postgresCpuPercent:50, oracleMemoryUsedMiB:16000, postgresMemoryUsedMiB:12000, oracleWaitMs:2, postgresWalBytesPerSecond:1024, postgresLockWaitCount:0 }));
    await check('Normal runs precede diagnostics and keep their original statistics and markers', async () => {
      const mixed=structuredClone(fixture);mixed.diagnostics=structuredClone(diagnosticOnly.diagnostics);await load(mixed);
      assert.equal(await page.locator('#run-selector').inputValue(), 'test-a');
      assert.deepEqual(await page.locator('#run-selector option').evaluateAll(options=>options.map(o=>o.value)),[...fixture.runs.map(r=>r.id),'diagnostic:host-preflight-synthetic']);
      assert.doesNotMatch(await page.locator('#cpu-chart svg').textContent(), /Ön ölçüm/);
      assert.match(await page.locator('#cpu-chart svg').textContent(), /Başlangıç/);
      assert.match(await page.locator('#collection-summary').innerText(), /17 kaynak örneği \(7 aktarım, 10 ayrı ölçüm provası\)/);
      await page.locator('#run-selector').selectOption('diagnostic:host-preflight-synthetic');
      assert.match(await page.locator('#cpu-chart svg').textContent(), /Ön ölçüm başlangıcı/);
      assert.equal(await page.locator('#kpi-runs').innerText(), '3 / 18');
      assert.equal(await page.locator('#kpi-throughput').innerText(), '5.000 kayıt/sn');
      assert.equal(await page.locator('#run-table tr').count(), 5);
      await page.locator('#run-selector').selectOption('test-a');
      assert.doesNotMatch(await page.locator('#sample-info').innerText(), /Ölçüm provası/);
      assert.equal(await page.locator('#rate-chart svg').count(), 1);
      for (const mutate of [d=>d.diagnostics[0].id='test-a',d=>d.runs[0].id='diagnostic:host-preflight-synthetic']) {
        const bad=structuredClone(mixed);mutate(bad);await load(bad);
        assert.equal(await page.locator('#message.error').count(), 1);
        assert.equal(await page.locator('#kpi-runs').innerText(), '3 / 18');
      }
    });
    await check('The source sample limit counts runs and diagnostics together', async () => {
      const bad=structuredClone(fixture);bad.runs=[bad.runs[0]];
      bad.runs[0].samples=Array.from({length:50000},(_,i)=>({tSeconds:i,phase:'transfer'}));
      bad.diagnostics=[{...structuredClone(diagnosticOnly.diagnostics[0]),samples:Array.from({length:50001},(_,i)=>({tSeconds:i,phase:'baseline'}))}];
      await load(bad);
      assert.equal(await page.locator('#message.error').count(), 1);
      assert.match(await page.locator('#message').innerText(), /100\.000 kaynak örneği/);
      assert.equal(await page.locator('#kpi-runs').innerText(), '3 / 18');
    });
    await check('Import computes median and throughput, excludes warmup/failure, retains audit records', async () => {
      await load(fixture);assert.equal(await page.locator('#message.error').count(), 0);
      assert.equal(await page.locator('#kpi-runs').innerText(), '3 / 18');
      assert.equal(await page.locator('#kpi-throughput').innerText(), '5.000 kayıt/sn');
      assert.equal(await page.locator('#kpi-validated').innerText(), '100 bin');
      assert.equal(await page.locator('#kpi-failed').innerText(), '2');
      assert.equal(await page.locator('#run-table tr').count(), 5);
      assert.equal(await page.locator('#stage-table tr').first().locator('td').nth(2).innerText(), '20');
      assert.equal(await page.locator('#stage-table tr').first().locator('td').nth(3).innerText(), '10 – 30');
      assert.equal(await page.locator('svg').count(), 8);
      assert.match(await page.locator('#db-details').innerText(), /3 \/ 100 \/ 100/);
      assert.match(await page.locator('#events').innerText(), /Synthetic background work/);
      assert.equal(await page.evaluate(() => window.untrustedExecuted), undefined);
      assert.match(await page.locator('#collection-notes').innerText(), /Synthetic collection note/);
      assert.equal(await page.locator('#collection-sources img').count(), 0);
    });
    await check('Missing samples leave gaps, zero is retained, CPU lines share the same scale', async () => {
      const ratePath = await page.locator('#rate-chart svg path').getAttribute('d');
      assert.equal((ratePath.match(/M/g)||[]).length, 2);
      assert.equal(await page.locator('#cpu-chart svg path').count(), 2);
      assert.match(await page.locator('#db-details').innerText(), /0/);
      assert.match(await page.locator('[data-metric="rowsPerSecond"] dd').innerText(), /6 \/ 7 örnek · kısmi/);
      assert.match(await page.locator('[data-metric="postgresLockWaitCount"] dd').innerText(), /7 \/ 7 örnek · tam/);
      assert.match(await page.locator('#coverage-scope').innerText(), /kapsamı doğrulanamaz/);
      await page.locator('#run-selector').selectOption('test-b');
      assert.equal(await page.locator('.timeline-grid svg').count(), 0);
      assert.equal(await page.locator('.timeline-grid .chart-empty').count(), 6);
      assert.match(await page.locator('#coverage-summary').innerText(), /Kaynak örneği yok/);
      await page.locator('#run-selector').selectOption('test-a');
    });
    await check('Missing and null DB metrics remain unmeasured while zero is a collected value', async () => {
      const partial=structuredClone(fixture);
      partial.runs[0].samples.forEach(s=>{delete s.oracleCpuPercent;s.postgresCpuPercent=null;});
      await load(partial);
      assert.equal(await page.locator('#message.error').count(), 0);
      assert.equal(await page.locator('[data-metric="oracleCpuPercent"] dd').innerText(), 'Ölçülmedi');
      assert.equal(await page.locator('[data-metric="postgresCpuPercent"] dd').innerText(), 'Ölçülmedi');
      assert.match(await page.locator('[data-metric="postgresLockWaitCount"] dd').innerText(), /7 \/ 7 örnek · tam/);
      assert.equal(await page.locator('#db-cpu-chart svg').count(), 0);
      assert.match(await page.locator('#coverage-summary').innerText(), /16 \/ 18 metrik/);
      await load(fixture);
    });
    await check('Invalid values and false validation claims are rejected without replacing good data', async () => {
      for (const mutate of [d=>d.runs[0].samples[0].hostCpuPercent=101,d=>d.runs[0].rowsCommitted=1,d=>d.runs[1].id=d.runs[0].id,d=>d.runs[0].samples.reverse(),d=>d.runs[0].durationSeconds=0,d=>d.collection=null,d=>d.collection=[],d=>d.collection.state='unknown',d=>d.collection.state=['ready'],d=>d.collection.notes='invalid',d=>d.collection.notes=[123],d=>d.collection.sources=[]]) {
        const bad=structuredClone(fixture);mutate(bad);await load(bad);
        assert.equal(await page.locator('#message.error').count(), 1);
        assert.equal(await page.locator('#kpi-runs').innerText(), '3 / 18');
        assert.match(await page.locator('#collection-notes').innerText(), /Synthetic collection note/);
      }
      await load(fixture);
    });
    await check('Desktop and mobile views have no page overflow', async () => {
      for (const width of [1440, 1024, 768, 390]) {
        await page.setViewportSize({width,height:1000});
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth<=window.innerWidth), 'Horizontal page overflow at '+width);
      }
      await page.setViewportSize({width:1440,height:1080});
      await page.screenshot({path:path.join(temp,'populated-desktop.png'), fullPage:true});
    });
    await check('CSV preserves all runs and neutralizes spreadsheet formulas', async () => {
      const [download]=await Promise.all([page.waitForEvent('download'),page.locator('#download-csv').click()]);
      const file=path.join(temp,'results.csv');await download.saveAs(file);const csv=await fs.readFile(file,'utf8');
      assert.match(csv,/"'\=SUM\(A1:A2\)"/);assert.equal(csv.split('\r\n').length,6);
    });
    await check('Filled HTML download survives reopening offline, with injected script text escaped', async () => {
      const [download]=await Promise.all([page.waitForEvent('download'),page.locator('#download-html').click()]);
      const file=path.join(temp,'populated.html');await download.saveAs(file);
      await page.goto(pathToFileURL(file).href);
      assert.equal(await page.locator('#kpi-runs').innerText(),'3 / 18');
      assert.equal(await page.locator('svg').count(),8);
      assert.equal(await page.evaluate(()=>window.untrustedExecuted),undefined);
      assert.match(await page.locator('#method-facts').innerText(),/<\/script>/);
      assert.match(await page.title(), /SKY - DIT · Data Integration Tool/);
      assert.match(await page.locator('#collection-notes').innerText(), /Synthetic collection note: <\/script>/);
      assert.match(await page.locator('#collection-sources').innerText(), /Synthetic <img/);
      const reopened=JSON.parse(await page.locator('#report-data').textContent());
      assert.deepEqual(reopened.collection,fixture.collection);
    });
    await check('Printable report renders to PDF', async () => {
      await page.emulateMedia({media:'print'});
      await page.pdf({path:path.join(temp,'verification-only.pdf'),format:'A4',landscape:true,printBackground:true});
      assert.ok((await fs.stat(path.join(temp,'verification-only.pdf'))).size>10000);
      await page.emulateMedia({media:'screen'});
    });
    await page.goto(pathToFileURL(reportPath).href);
    await page.screenshot({path:path.join(temp,'template-desktop.png'),fullPage:true});
    await page.setViewportSize({width:390,height:844});
    await page.screenshot({path:path.join(temp,'template-mobile.png'),fullPage:true});
    await check('Report makes no external HTTP requests and has no JavaScript errors', async () => {assert.deepEqual(requests,[]);assert.deepEqual(errors,[]);});
    console.log('Verification artifacts: '+temp);
    console.log('Delivered template remains empty: '+reportPath);
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
