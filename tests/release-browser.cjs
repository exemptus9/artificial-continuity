/* Browser UI -> native persistence -> downloaded backup -> scoped core -> clean restore. */
const {chromium}=require('playwright');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),http=require('node:http'),os=require('node:os'),cp=require('node:child_process');
const root=path.resolve(__dirname,'../site');
const server=http.createServer((req,res)=>{const p=path.resolve(root,'.'+(req.url==='/'?'/index.html':req.url.split('?')[0]));if(!p.startsWith(root+path.sep)){res.writeHead(403);return res.end();}try{res.setHeader('Content-Type',({'.html':'text/html','.js':'application/javascript','.css':'text/css'})[path.extname(p)]||'application/octet-stream');res.end(fs.readFileSync(p));}catch{res.writeHead(404);res.end();}});
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));let browser;const temp=fs.mkdtempSync(path.join(os.tmpdir(),'continuity-release-'));fs.mkdirSync('test-artifacts',{recursive:true});
 try{
 browser=await chromium.launch({headless:true});const ctx=await browser.newContext({acceptDownloads:true}),page=await ctx.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
 const base='http://127.0.0.1:'+server.address().port;
 await page.goto(base);await page.waitForFunction(()=>typeof S!=='undefined'&&S&&!busy);
 // Delay the real draft read: controls must remain inert until hydration finishes.
 await page.evaluate(()=>{const read=store.read.bind(store);store.read=async(...args)=>{if(args[0]==='drafts')await new Promise(r=>setTimeout(r,250));return read(...args);};});
 await page.locator('main [data-action="add-source"]').first().click();
 await page.locator('#sourceTitle').fill('Source conversation');
 const exact='Synthetic release fixture\r\nFirst — “quoted” α\n\n    indented line\n\tTabbed line\nFinal...';
 await page.locator('#sourceText').evaluate((el,text)=>{const d=new DataTransfer();d.setData('text/plain',text);el.dispatchEvent(new ClipboardEvent('paste',{clipboardData:d,bubbles:true,cancelable:true}));},exact);
 await page.locator('#sourceForm button.primary').click();await page.getByRole('heading',{name:'Source conversation',exact:true}).waitFor();
 assert.equal(await page.evaluate(()=>S.sources[0].text),exact);
 await page.reload();await page.getByRole('heading',{name:'Source conversation',exact:true}).waitFor();
 const sourceDownload=page.waitForEvent('download');await page.locator('[data-action="download-source"]').click();const sd=await sourceDownload;await sd.saveAs(path.join(temp,'original.txt'));assert.equal(fs.readFileSync(path.join(temp,'original.txt'),'utf8'),exact);
 await page.locator('[data-nav="backup"]').click();const [download]=await Promise.all([page.waitForEvent('download'),page.locator('#main [data-action="export"]').click()]);const backup=path.join(temp,'browser.json');await download.saveAs(backup);
 const fresh=await browser.newContext();const rp=await fresh.newPage();await rp.goto(base+'/#backup');await rp.locator('#backupFile').setInputFiles(backup);await rp.locator('#restore:enabled').waitFor();rp.once('dialog',d=>d.accept());await rp.locator('#restore').click();await rp.waitForFunction(()=>typeof S!=='undefined'&&S?.sources?.length===1&&!busy);await rp.reload();await rp.waitForFunction(()=>typeof S!=='undefined'&&S?.sources?.length===1);assert.equal(await rp.evaluate(()=>S.sources[0].text),exact);await fresh.close();
 const r=cp.spawnSync('python3',[path.resolve(__dirname,'../tools/release_smoke.py'),'--destination',path.join(temp,'core'),'--browser-export',backup],{encoding:'utf8'});assert.equal(r.status,0,r.stderr||r.stdout);const receipt=JSON.parse(r.stdout);assert.equal(receipt.browser_import.copied_sources,1);
 const original=JSON.parse(fs.readFileSync(backup,'utf8')).state.sources[0];const imported=receipt.browser_import.receipts[0];assert.equal(imported.sha256,require('node:crypto').createHash('sha256').update(original.text).digest('hex'));
 assert.deepEqual(errors,[]);await page.screenshot({path:'test-artifacts/release-browser.png',fullPage:true});
 fs.writeFileSync('test-artifacts/release-receipt.json',JSON.stringify({...receipt,browser_ui_original_equals_restored:true,source_heading_contract:'Source conversation',delayed_draft_hydration_ms:250,browser:await browser.version()},null,2));
 console.log('PASS browser capture/reload/source download/backup/clean browser restore/scoped core import/idempotent repeat/core restore: exact UTF-8 equality');
 }finally{await browser?.close();await new Promise(r=>server.close(r));fs.rmSync(temp,{recursive:true,force:true});}
})().catch(e=>{console.error(e);process.exitCode=1});
