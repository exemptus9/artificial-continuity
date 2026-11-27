#!/usr/bin/env python3
"""Exercise playback gain, original downloads, media replacement and recovery using synthetic audio."""
import argparse, hashlib, io, json, math, struct, sys, tempfile, threading, time, wave, zipfile
from pathlib import Path
from unittest.mock import patch
from playwright.sync_api import sync_playwright,expect
src=Path(__file__).resolve().parents[1]/'mint'
sys.path.insert(0,str(src))
import recall_desk as desk,recall_web as web
with io.BytesIO() as buf:
    with wave.open(buf,"wb") as f:
        f.setparams((1,2,16000,0,"NONE",""));f.writeframes(b''.join(struct.pack('<h',round(450*math.sin(2*math.pi*i/40))) for i in range(16000))*8)
    wav=buf.getvalue()
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--chromium',help='Chromium executable, or use the Playwright install')
args=parser.parse_args()
def seed(arc):
    for i in range(2):
        uid=f"synthetic-clip-{i}"
        clip=dict(id=uid,title="Quiet fixture",startedAtMillis=int(time.time()*1000)-10000,
                  durationMs=8000,category="Test",reason="Synthetic audio",transcript="",
                  audio=f"audio/{uid}.wav",sha256=hashlib.sha256(wav).hexdigest())
        payload=io.BytesIO()
        with zipfile.ZipFile(payload,"w") as package:
            package.writestr("index.json",json.dumps(dict(schema=desk.FORMAT,clips=[clip])))
            package.writestr(clip["audio"],wav)
        binary=payload.getvalue()
        arc.begin(dict(clip,kind="clip",bytes=len(binary),sha256=hashlib.sha256(binary).hexdigest()))
        arc.put(uid,0,binary)
probe="""(()=>{
 const Native=window.AudioContext;window.gainQA={ctx:[],samples:[],sources:[],fail:false};
 window.AudioContext=class extends Native{constructor(...args){super(...args);gainQA.ctx.push(this)}};
 const connect=AudioNode.prototype.connect;
 AudioNode.prototype.connect=function(next,...args){
   const result=connect.call(this,next,...args);
   if(next instanceof AudioDestinationNode){
      const analyser=this.context.createAnalyser();analyser.fftSize=1024;
      connect.call(this,analyser);gainQA.samples.push(analyser);
   }
   return result;
 };
 const create=Native.prototype.createMediaElementSource;
 Native.prototype.createMediaElementSource=function(audio){
    if(gainQA.fail)throw new Error('Synthetic unavailable processing');
    gainQA.sources.push(audio);return create.call(this,audio);
 };
 gainQA.peak=()=>Math.max(0,...gainQA.samples.map(a=>{const b=new Float32Array(1024);a.getFloatTimeDomainData(b);return Math.max(...b.map(x=>Math.abs(x)))}));
})()"""
def until(page,expr):
    for i in range(80):
        if page.evaluate(expr):return
        page.wait_for_timeout(50)
    raise AssertionError(expr+" "+str(page.evaluate("({contexts:gainQA.ctx.map(c=>c.state),sources:gainQA.sources.length,a:gainQA.peak(),help:document.querySelector('dialog')?.innerText?.slice(0,350)})")))
with tempfile.TemporaryDirectory() as tmp,patch.multiple(desk,ROOT=Path(tmp),DB=Path(tmp)/'clips.sqlite3'),sync_playwright() as p:
    arc=desk.archive();seed(arc)
    server=web.web_server(arc,src/'web',0);threading.Thread(target=server.serve_forever,daemon=True).start()
    b=p.chromium.launch(executable_path=args.chromium,args=['--no-sandbox','--disable-dev-shm-usage'])
    try:
        for w,mobile in [(1280,False),(360,True)]:
            context=b.new_context(viewport={'width':w,'height':800},is_mobile=mobile,has_touch=mobile)
            page=context.new_page();page.add_init_script(probe);errors=[]
            page.on('pageerror',lambda exc:errors.append(str(exc)))
            page.goto(web.grant(arc.root,server.server_port)+'#clip/synthetic-clip-0')
            boost=page.get_by_label('Playback boost');audio=page.locator('dialog audio').first
            expect(boost).to_have_value('0')
            original=page.request.get(f'http://127.0.0.1:{server.server_port}/api/media/clip/synthetic-clip-0').body()
            assert original[:4]==b'RIFF'
            page.get_by_label("Remember in this tab").check()
            audio.evaluate("a=>{a.loop=true}")
            boost.select_option('6')
            audio.evaluate('a=>a.play()')
            until(page,'gainQA.peak()>.012')
            def measure(n):
                boost.select_option(str(n));page.wait_for_timeout(400)
                return page.evaluate("gainQA.peak()")
            one=measure(0);two=measure(6);four=measure(12)
            assert 1.95<two/one<2.05 and 3.90<four/one<4.08,(one,two,four)
            page.get_by_role('button',name='Voice focus',exact=True).click()
            page.get_by_role('button',name='Voice focus on').click()
            assert page.evaluate('gainQA.sources.length')==1
            with page.expect_download() as d:page.get_by_role('link',name='Download original').click()
            assert Path(d.value.path()).read_bytes()==original
            page.get_by_role('button',name='Close item').click()
            until(page,"gainQA.ctx.every(c=>c.state==='closed')")
            page.evaluate("location.hash='sessions'")
            upload=page.locator('input[type=file]').first
            upload.set_input_files({'name':'one.wav','mimeType':'audio/wav','buffer':wav})
            sa=page.locator('#session-audio');expect(sa).to_be_visible();expect(boost).to_have_value('12')
            sa.evaluate('a=>a.play()');until(page,'gainQA.sources.length===2');page.wait_for_timeout(100)
            def swap(name,blob):upload.set_input_files({'name':name,'mimeType':'audio/wav','buffer':blob})
            swap('broken.wav',b'invalid audio data')
            expect(boost).to_be_disabled()
            swap('two.wav',wav)
            expect(boost).to_be_enabled();expect(boost).to_have_value('12')
            sa.evaluate('a=>a.play()');page.wait_for_timeout(150)
            assert page.evaluate('gainQA.sources.length')==2
            assert page.evaluate('document.documentElement.scrollWidth<=window.innerWidth')
            page.evaluate("location.hash='clip/synthetic-clip-1'")
            until(page,"gainQA.ctx.every(c=>c.state==='closed')")
            page.evaluate("gainQA.fail=true")
            boost.select_option('6')
            expect(boost).to_have_value('0');expect(page.get_by_text('Playback processing is unavailable. Use the original audio level.')).to_be_visible()
            page.locator('dialog audio').first.evaluate('a=>a.play()')
            until(page,"document.querySelector('dialog audio').currentTime>.1")
            assert not errors,errors
            print(w, 'px actual gain ratios',round(two/one,2),round(four/one,2),'one source per player; reopen, file-error recovery, native fallback and original-byte download passed.')
            context.close()
    finally:b.close();server.shutdown();server.server_close()
