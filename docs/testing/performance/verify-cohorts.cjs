const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs/promises');
const { chromium } = require(require.resolve('playwright', {paths:[path.resolve(__dirname,'../../../frontend')]}));
(async()=>{
  const browser=await chromium.launch({headless:true,channel:'msedge'});
  try {
    const page=await browser.newPage();const errors=[],requests=[];page.on('pageerror',e=>errors.push(e.message));
    page.on('request',request=>{if(/^https?:/.test(request.url()))requests.push(request.url());});
    const reportPath=path.resolve('.data/performance/sky-dit-uat/sky-dit-report.html');
    const html=await fs.readFile(reportPath,'utf8');
    const resultsPath=path.join(path.dirname(reportPath),'sky-dit-results.json');
    const resultsJson=await fs.readFile(resultsPath,'utf8');
    const snapshotPath=path.join(path.dirname(reportPath),'pre-adaptive-v2-baseline.json');
    const snapshotJson=await fs.readFile(snapshotPath,'utf8');
    const snapshot=JSON.parse(snapshotJson);
    const template=await fs.readFile(path.join(__dirname,'rapor.html'),'utf8');
    const pattern=/(<script id="report-data" type="application\/json">)(.*?)(<\/script>)/s;
    const original=JSON.parse(html.match(pattern)[2]);
    const render=async fixture=>{
      await page.setContent(template.replace(pattern,(_,a,b,c)=>a+JSON.stringify(fixture).replace(/</g,'\\u003c')+c));
      assert.equal(await page.locator('#message.error').count(),0,'Fixture must render successfully');
    };
    await render(original);
    const baselineRow=()=>page.locator('#stage-table tr').filter({hasText:'500.000'}).first().locator('td').nth(2).textContent();
    const before=await baselineRow();
    const data=JSON.parse(html.match(pattern)[2]);
    const source=data.runs.find(r=>r.targetRows===500000&&r.status==='completed');
    // Fixture stays in browser memory. Never write synthetic runs to disk.
    data.runs=data.runs.filter(r=>!r.cohort||r.cohort==='baseline');
    data.findings=[];
    data.runs.push({...structuredClone(source),id:'synthetic-cohort-check',cohort:'memory-budget-1g',durationSeconds:10});
    data.runs.push({...structuredClone(source),id:'synthetic-fast-check',cohort:'synthetic-fast-check',durationSeconds:5});
    data.collection.state='paused';
    await render(data);
    assert.match(await page.locator('#collection-state').textContent(),/ara verildi/);
    const result={before,after:await baselineRow(),comparison:await page.locator('body').textContent(),toolbar:await page.locator('.toolbar').count()};
    assert.equal(result.after,result.before,'Different resource policies must not share a median');
    assert.match(result.comparison,/1 GiB/);assert.equal(result.toolbar,0);assert.deepEqual(errors,[]);
    assert.match(await page.locator('#method-facts').textContent(),/Ortam aktarım bütçesi/);
    assert.match(await page.locator('#run-selector').textContent(),/1 GiB ortam bütçesi/);
    assert.match(await page.locator('#kpi-throughput').textContent(),/^100\.000 kayıt\/sn$/);
    assert.equal(await page.locator('#kpi-runs').textContent(),'11 / 24');
    // A volume measured only in the new cohort replaces its empty planned slot;
    // it must not add a second three-run plan or disappear from volume coverage.
    data.runs=data.runs.filter(r=>!r.cohort||r.cohort==='baseline');
    data.runs.push({...structuredClone(source),id:'synthetic-1m-only',cohort:'memory-budget-1g',
      targetRows:1000000,rowsRead:1000000,rowsCommitted:1000000,durationSeconds:200,samples:[],events:[],
      validationEvidence:{sourceCount:1000000,targetCount:1000000,targetTotalRows:1000000,expected:1000000,
        sourceSha256:'synthetic-browser-only',targetSha256:'synthetic-browser-only'}});
    await render(data);
    assert.equal(await page.locator('#kpi-runs').textContent(),'10 / 18','New-cohort-only volume must not be planned twice');
    assert.equal(await page.locator('#stage-strip .stage').nth(3).locator('small').textContent(),'1/3 ölçüm tamamlandı');
    assert.equal(await page.locator('#stage-table tr').nth(3).locator('td').nth(1).textContent(),'0 / 3','Baseline table must remain separate');
    assert.equal(await page.locator('#comparison-table tr').filter({hasText:'1.000.000'}).count(),1,'Do not invent an unmeasured baseline cohort/volume pair');
    assert.deepEqual(errors,[]);
    console.log('COHORT_RENDER_OK');

    const failures=[];
    const check=async(name,fn)=>{
      try {await fn();console.log('PASS '+name);}
      catch(error){failures.push(error);console.error('FAIL '+name+'\n'+error.stack);}
    };
    const GiB=1024**3;
    const adaptive=structuredClone(data);
    adaptive.runs=[];
    const makeRun=(cohort,repetition,durationSeconds,extra={})=>({
      ...structuredClone(data.runs.at(-1)),id:`fixture-${cohort}-${repetition}`,cohort,repetition,durationSeconds,
      ...({'adaptive-read-1g':{memoryBudgetBytes:GiB},'adaptive-read-2g':{memoryBudgetBytes:2*GiB},'adaptive-read-5g':{memoryBudgetBytes:5*GiB},'adaptive-read-v2-1g':{memoryBudgetBytes:GiB},'adaptive-read-v2-2g':{memoryBudgetBytes:2*GiB},'adaptive-read-v2-5g':{memoryBudgetBytes:5*GiB}}[cohort]||{}),
      ...extra
    });
    // Three measured trials per distinct implementation/budget. No disk fixtures.
    for(const [cohort,times] of [
      ['memory-budget-1g',[700,800,900]],['adaptive-read-1g',[300,400,500]],
      ['adaptive-read-2g',[200,300,400]],['adaptive-read-5g',[250,350,450]]
    ]) for(const [i,seconds] of times.entries()) adaptive.runs.push(makeRun(cohort,i+1,seconds));
    adaptive.runs.push(makeRun('adaptive-read-2g',4,1,{status:'failed',validationStatus:'failed'}));
    adaptive.runs.push(makeRun('adaptive-read-2g',5,2,{validationStatus:'pending'}));
    adaptive.runs.push(makeRun('adaptive-read-2g',6,3,{purpose:'warmup'}));
    const detailed=adaptive.runs.find(r=>r.cohort==='adaptive-read-5g');
    detailed.memoryBudgetBytes=5*GiB;
    detailed.transferTelemetry={readSeconds:100,writeSeconds:20,commitSeconds:3,
      querySeconds:10,fetchSeconds:40,lobSeconds:30,decodeSeconds:20,encodeSeconds:5,checkpointSeconds:12,
      fetchCalls:1000001,rowsDecoded:1000000,lobCells:2000000,
      requestedBudgetBytes:5*GiB,effectiveBudgetBytes:Math.floor(1.96*GiB),
      readWindowBytes:128*1024**2,writeWindowBytes:32*1024**2,
      fetchRows:4096,minFetchRows:256,maxFetchRows:8192,batchCount:245,gcCount:0,gcSeconds:0};
    await check('conflicting adaptive cohort and requested budgets are rejected before pooling',async()=>{
      for(const cohort of ['adaptive-read-1g','adaptive-read-2g','adaptive-read-5g','adaptive-read-v2-1g','adaptive-read-v2-2g','adaptive-read-v2-5g'])for(const conflict of ['root','flat','nested','missing-root']){
        const invalid=structuredClone(adaptive);const run=makeRun(cohort,99,100);
        invalid.runs.push(run);
        const wrong=cohort.endsWith('-5g')?GiB:5*GiB;
        if(conflict==='root')run.memoryBudgetBytes=wrong;
        else if(conflict==='missing-root')delete run.memoryBudgetBytes;
        else run.transferTelemetry=conflict==='flat'?{requestedBudgetBytes:wrong}:{diagnostics:{requestedBudgetBytes:wrong}};
        await page.setContent(template.replace(pattern,(_,a,b,c)=>a+JSON.stringify(invalid).replace(/</g,'\\u003c')+c));
        assert.equal(await page.locator('#message.error').count(),1,'Reject '+cohort+' '+conflict+' budget mismatch');
        assert.equal(await page.locator('#comparison-table tr').count(),0,'Never aggregate mislabeled trials');
      }
    });
    await check('adaptive budgets have distinct Turkish labels and isolated medians',async()=>{
      await render(adaptive);
      const rows=await page.locator('#comparison-table tr').evaluateAll(rows=>rows.map(r=>[...r.cells].map(c=>c.textContent)));
      assert.equal(rows.length,4,'No unmeasured baseline/volume pair');
      for(const [label,median] of [['1 GiB ortam bütçesi · önceki uygulama','800'],
        ['Uyarlamalı okuma · ilk sürüm · 1 GiB istenen bütçe','400'],['Uyarlamalı okuma · ilk sürüm · 2 GiB istenen bütçe','300'],
        ['Uyarlamalı okuma · ilk sürüm · 5 GiB istenen bütçe','350']]) {
        const row=rows.find(row=>row[0]===label);
        assert.ok(row,'Missing distinct cohort label: '+label);
        assert.equal(row[1],'1.000.000');assert.equal(row[2],'3 / 3');assert.equal(row[3],median);
        assert.match(await page.locator('#run-selector').textContent(),new RegExp(label.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')));
      }
      assert.equal(await page.locator('#kpi-runs').textContent(),'13 / 27','Twelve valid and one pending completed measurement; warmup and failed excluded');
      assert.equal(await page.locator('#kpi-throughput').textContent(),'3.333,3 kayıt/sn','Invalid, pending and warmup trials cannot inflate medians');
      assert.equal(await page.locator('#stage-strip .stage').nth(3).locator('small').textContent(),'3/3 ölçüm tamamlandı');
      assert.equal(await page.locator('#stage-table tr').nth(3).locator('td').nth(1).textContent(),'0 / 3');
    });
    const detailValue=async label=>page.locator('#transfer-details .fact').filter({has:page.getByText(label,{exact:true})}).locator('dd').textContent();
    const versioned=structuredClone(adaptive);
    const telemetry=budget=>({readSeconds:100,writeSeconds:20,commitSeconds:3,diagnostics:{
      querySeconds:10,fetchSeconds:40,lobSeconds:30,decodeSeconds:20,encodeSeconds:5,checkpointSeconds:12,
      requestedBudgetBytes:budget*GiB,effectiveBudgetBytes:budget===1?GiB:Math.floor(1.96*GiB),
      readWindowBytes:128*1024**2,writeWindowBytes:32*1024**2,fetchRows:4096,gcCount:0,gcSeconds:0}});
    versioned.runs=[makeRun('adaptive-read-1g',1,900,{transferTelemetry:telemetry(1)}),
      makeRun('adaptive-read-2g',1,800,{transferTelemetry:telemetry(2)}),
      makeRun('adaptive-read-5g',1,700,{status:'failed',validationStatus:'pending',rowsCommitted:0,
        transferTelemetry:telemetry(5),targetPublishSeconds:33.7})];
    for(const [budget,times] of [[1,[90,100,110]],[2,[190,200,210]],[5,[290,300,310]]])
      for(const [i,seconds] of times.entries())versioned.runs.push(makeRun(`adaptive-read-v2-${budget}g`,i+1,seconds,
        {transferTelemetry:telemetry(budget),...(budget===5&&i===0?{targetPublishSeconds:42.5}:{})}));
    await check('v2 timeout-fix cohorts stay separate from two initial successes and failed publication',async()=>{
      await render(versioned);
      const rows=await page.locator('#comparison-table tr').evaluateAll(rows=>rows.map(r=>[...r.cells].map(c=>c.textContent)));
      assert.equal(rows.length,6,'Each version and requested budget needs its own comparison row');
      for(const [label,count,median,speed] of [
        ['Uyarlamalı okuma · ilk sürüm · 1 GiB istenen bütçe','1 / 3','900','1.111,1'],
        ['Uyarlamalı okuma · ilk sürüm · 2 GiB istenen bütçe','1 / 3','800','1.250'],
        ['Uyarlamalı okuma · ilk sürüm · 5 GiB istenen bütçe','0 / 3','—','—'],
        ['Uyarlamalı okuma v2 · yayınlama zaman aşımı düzeltmesi · 1 GiB istenen bütçe','3 / 3','100','10.000'],
        ['Uyarlamalı okuma v2 · yayınlama zaman aşımı düzeltmesi · 2 GiB istenen bütçe','3 / 3','200','5.000'],
        ['Uyarlamalı okuma v2 · yayınlama zaman aşımı düzeltmesi · 5 GiB istenen bütçe','3 / 3','300','3.333,3']
      ]) {
        const row=rows.find(row=>row[0]===label);
        assert.ok(row,'Missing version-specific label: '+label);
        assert.deepEqual(row.slice(1,5),['1.000.000',count,median,speed]);
        assert.ok((await page.locator('#run-selector').textContent()).includes(label));
        if(count==='0 / 3')assert.deepEqual(row.slice(5),['—','—','—','—'],'Failed cohort has no invented CPU or RAM');
      }
      assert.equal(await page.locator('#kpi-runs').textContent(),'11 / 33','Six distinct cohorts consume one planned stage plus fifteen extra trials');
      assert.equal(await page.locator('#stage-strip .stage').nth(3).locator('small').textContent(),'3/3 ölçüm tamamlandı');
      assert.equal(await page.locator('#stage-table tr').nth(3).locator('td').nth(1).textContent(),'0 / 3');
      for(const width of [1440,390]) {
        await page.setViewportSize({width,height:1000});
        assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Version labels must fit at '+width);
      }
      await page.setViewportSize({width:1440,height:1000});
    });
    await check('nested diagnostics preserve requested versus effective clamp for both versions',async()=>{
      await render(versioned);
      for(const [id,requested,effective] of [
        ['fixture-adaptive-read-1g-1','1 GiB','1 GiB'],['fixture-adaptive-read-2g-1','2 GiB','1,96 GiB'],
        ['fixture-adaptive-read-5g-1','5 GiB','1,96 GiB'],['fixture-adaptive-read-v2-1g-1','1 GiB','1 GiB'],
        ['fixture-adaptive-read-v2-2g-1','2 GiB','1,96 GiB'],['fixture-adaptive-read-v2-5g-1','5 GiB','1,96 GiB']
      ]) {
        await page.locator('#run-selector').selectOption(id);
        assert.ok((await detailValue('İstenen bütçe')).startsWith(requested),id);
        assert.ok((await detailValue('Etkili bütçe')).startsWith(effective),id);
        assert.equal(await detailValue('İmleç okuma'),'40 sn',id);
        assert.equal(await detailValue('Fetch satırı'),'4.096',id);
        assert.equal(await detailValue('GC süresi'),'0 sn',id);
      }
    });
    await check('target publication uses only recorded run duration and remains separate from staging',async()=>{
      await render(versioned);
      const label='Hedefe yayınlama (HEDEFE_YAZ)';
      assert.equal(await page.getByText(label,{exact:true}).count(),1,'Target publication fact is missing');
      await page.locator('#run-selector').selectOption('fixture-adaptive-read-v2-5g-1');
      assert.equal(await detailValue(label),'42,5 sn');
      assert.equal(await detailValue('Yazma'),'20 sn');assert.equal(await detailValue('Commit'),'3 sn');
      assert.equal(await detailValue('Toplam okuma'),'100 sn');
      assert.equal(await page.locator('#run-table tr').filter({hasText:'fixture-adaptive-read-v2-5g-1'}).locator('td').nth(3).textContent(),'290');
      await page.locator('#run-selector').selectOption('fixture-adaptive-read-5g-1');
      assert.equal(await detailValue(label),'33,7 sn','Recorded failed step duration remains evidence, not a successful publish');
      await page.locator('#run-selector').selectOption('fixture-adaptive-read-v2-5g-2');
      assert.equal(await detailValue(label),'Ölçülmedi / belirtilmedi','Never infer publication duration from totals or staging phases');
      const note=await page.locator('#target-publish-note').textContent();
      assert.match(note,/km_step_journal/);assert.match(note,/tamamlanma.*başlangıç/);
      assert.match(note,/kilit.*ön kontrol.*sayım.*DML.*commit/i);
      assert.match(note,/COPY/);assert.match(note,/toplanmaz/);
      const missing=structuredClone(versioned);
      missing.runs[0].targetPublishSeconds=null;
      missing.runs[0].transferTelemetry.targetPublishSeconds=999;
      await render(missing);
      assert.equal(await detailValue(label),'Ölçülmedi / belirtilmedi','Only run.targetPublishSeconds is authoritative');
      missing.runs[0].targetPublishSeconds=0;await render(missing);
      assert.equal(await detailValue(label),'0 sn','A recorded zero is different from missing duration');
    });
    await check('selected run shows exclusive read phases, clamped budget, fetch and zero GC',async()=>{
      await render(adaptive);await page.locator('#run-selector').selectOption(detailed.id);
      assert.equal(await page.locator('#transfer-details').count(),1,'Selected-run telemetry panel is missing');
      for(const [label,value] of [['Toplam okuma','100 sn'],['Sorgu / ilk sonuç','10 sn'],['İmleç okuma','40 sn'],
        ['LOB okuma','30 sn'],['Satır çözümleme','20 sn'],['Kodlama / sağlama','5 sn'],['İlerleme kaydı','12 sn'],
        ['Yazma','20 sn'],['Commit','3 sn'],['İmleç next çağrısı','1.000.001'],['Çözümlenen satır','1.000.000'],
        ['LOB hücresi','2.000.000'],['Fetch satırı','4.096'],['En küçük fetch','256'],['En büyük fetch','8.192'],
        ['Batch sayısı','245'],['GC sayısı','0'],['GC süresi','0 sn']]) assert.equal(await detailValue(label),value,label);
      assert.match(await detailValue('İstenen bütçe'),/^5 GiB/);
      assert.match(await detailValue('Etkili bütçe'),/^1,96 GiB/,'5 GiB requested must never be shown as effective');
      assert.match(await detailValue('Okuma penceresi'),/^128 MiB/);assert.match(await detailValue('Yazma penceresi'),/^32 MiB/);
      const note=await page.locator('#transfer-timing-note').textContent();
      assert.match(note,/okumanın birbirini dışlayan alt aşamaları/);
      assert.match(note,/Kodlama okuma dışındadır/);
      assert.match(note,/İlerleme kaydı ve GC.*örtüşür/);
      assert.match(note,/toplanmaz/);assert.match(note,/DB RPC sayısı değildir/);
      assert.match(await page.locator('#transfer-budget-note').textContent(),/heap.*sınır/);
      assert.equal(await page.locator('#run-table tr').filter({hasText:detailed.id}).locator('td').nth(3).textContent(),'250','Never sum overlapping diagnostics into wall time');
    });
    await check('old and partial telemetry show unavailable values without inheriting the selected run',async()=>{
      const partial=structuredClone(adaptive);
      partial.runs[0].memoryBudgetBytes=GiB;
      partial.runs[0].transferTelemetry={readSeconds:123,writeSeconds:45,commitSeconds:6,querySeconds:null,gcCount:null};
      partial.runs[1].transferTelemetry={requestedBudgetBytes:2*GiB,effectiveBudgetBytes:null,fetchRows:null};
      partial.runs[3].cohort='unknown-prior-run';delete partial.runs[3].memoryBudgetBytes;
      await render(partial);await page.locator('#run-selector').selectOption(detailed.id);
      await page.locator('#run-selector').selectOption(partial.runs[0].id);
      assert.equal(await page.locator('#transfer-details').count(),1,'Old runs also expose unavailable diagnostics');
      assert.equal(await detailValue('Toplam okuma'),'123 sn');assert.equal(await detailValue('Yazma'),'45 sn');
      assert.equal(await detailValue('Commit'),'6 sn');assert.match(await detailValue('İstenen bütçe'),/^1 GiB/);
      for(const label of ['Etkili bütçe','Sorgu / ilk sonuç','İmleç okuma','GC sayısı','Fetch satırı'])
        assert.equal(await detailValue(label),'Ölçülmedi / belirtilmedi',label);
      await page.locator('#run-selector').selectOption(partial.runs[1].id);
      assert.match(await detailValue('İstenen bütçe'),/^2 GiB/,'Telemetry request takes precedence over legacy run budget');
      assert.equal(await detailValue('Etkili bütçe'),'Ölçülmedi / belirtilmedi');
      await page.locator('#run-selector').selectOption(partial.runs[3].id);
      for(const value of await page.locator('#transfer-details dd').allTextContents()) assert.equal(value,'Ölçülmedi / belirtilmedi');
    });
    await check('each adaptive budget displays its own requested and effective limits',async()=>{
      const budgets=structuredClone(adaptive);
      budgets.runs=budgets.runs.filter(r=>r.id===detailed.id||r.id==='fixture-adaptive-read-1g-1'||r.id==='fixture-adaptive-read-2g-1');
      budgets.runs[0].memoryBudgetBytes=GiB;
      budgets.runs[0].transferTelemetry={effectiveBudgetBytes:GiB};
      budgets.runs[1].memoryBudgetBytes=2*GiB;
      budgets.runs[1].transferTelemetry={effectiveBudgetBytes:Math.floor(1.96*GiB)};
      await render(budgets);
      for(const [id,requested,effective] of [
        ['fixture-adaptive-read-1g-1','1 GiB','1 GiB'],
        ['fixture-adaptive-read-2g-1','2 GiB','1,96 GiB'],[detailed.id,'5 GiB','1,96 GiB']
      ]) {
        await page.locator('#run-selector').selectOption(id);
        assert.ok((await detailValue('İstenen bütçe')).startsWith(requested));
        assert.ok((await detailValue('Etkili bütçe')).startsWith(effective));
      }
    });
    await check('blank reports and measurement probes do not invent transfer diagnostics',async()=>{
      const blank=JSON.parse(template.match(pattern)[2]);
      assert.deepEqual(blank.runs,[],'Template must not contain measured or synthetic runs');
      await render(blank);
      assert.equal(await page.locator('#kpi-runs').textContent(),'0 / 18');
      for(const value of await page.locator('#transfer-details dd').allTextContents()) assert.equal(value,'Ölçülmedi / belirtilmedi');
      blank.diagnostics=[{id:'browser-only-probe',startedAt:'2026-10-24T10:00:00+03:00',durationSeconds:1,samples:[],events:[]}];
      await render(blank);
      assert.equal(await page.locator('#run-selector').inputValue(),'diagnostic:browser-only-probe');
      assert.equal(await page.locator('#kpi-throughput').textContent(),'—');
      for(const value of await page.locator('#transfer-details dd').allTextContents()) assert.equal(value,'Ölçülmedi / belirtilmedi');
    });
    await check('unrecognized legacy cohort labels stay plain text',async()=>{
      const legacy=structuredClone(adaptive);
      legacy.runs=[makeRun('constructor',1,100),makeRun('<img src=x onerror="window.injected=true">',1,200)];
      await render(legacy);
      assert.equal(await page.locator('#comparison-table tr').first().locator('td').first().textContent(),'constructor');
      assert.equal(await page.locator('#comparison-table img').count(),0);
      assert.equal(await page.evaluate(()=>window.injected),undefined);
    });
    await check('real report runs and summaries are preserved; no controls, network or browser errors',async()=>{
      await render(original);
      const rendered=JSON.parse(await page.locator('#report-data').textContent());
      assert.equal(snapshot.runs.length,19,'Expected all nineteen records in the v2 baseline snapshot');
      assert.ok(original.runs.length>=19,'Expected initial sixteen plus two passed and one failed trial');
      const persisted=JSON.parse(resultsJson);
      for(const run of snapshot.runs) {
        assert.deepEqual(rendered.runs.find(r=>r.id===run.id),run,'Preserve snapshot run in rendered HTML: '+run.id);
        assert.deepEqual(persisted.runs.find(r=>r.id===run.id),run,'Preserve snapshot run in real JSON: '+run.id);
      }
      assert.deepEqual(rendered.volumeSummaries,snapshot.volumeSummaries);
      assert.deepEqual(rendered.comparisonSummary,snapshot.comparisonSummary);
      assert.deepEqual(persisted.volumeSummaries,snapshot.volumeSummaries);
      assert.deepEqual(persisted.comparisonSummary,snapshot.comparisonSummary);
      assert.deepEqual(rendered.runs,original.runs);
      assert.deepEqual(rendered.volumeSummaries,original.volumeSummaries);
      assert.deepEqual(rendered.comparisonSummary,original.comparisonSummary);
      assert.equal(await fs.readFile(reportPath,'utf8'),html,'Real HTML must remain byte-for-byte unchanged');
      assert.equal(await fs.readFile(resultsPath,'utf8'),resultsJson,'Real JSON must remain byte-for-byte unchanged');
      assert.equal(await fs.readFile(snapshotPath,'utf8'),snapshotJson,'V2 snapshot must remain byte-for-byte unchanged');
      assert.equal(await page.locator('.toolbar, input[type="file"], a[download], button').count(),0);
      assert.deepEqual(errors,[]);
      assert.deepEqual(requests,[]);
      assert.equal(await page.locator('#run-table tr').count(),original.runs.length);
      for(const width of [1440,390]) {
        await page.setViewportSize({width,height:1000});
        assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'No horizontal page overflow at '+width);
      }
    });
    if(failures.length) throw new AggregateError(failures,`${failures.length} renderer checks failed`);
    console.log('ADAPTIVE_RENDER_OK');
  } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
