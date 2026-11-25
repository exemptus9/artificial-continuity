#!/usr/bin/env python3
"""Browser smoke: run against a disposable native receiver, or a synthetic phone's upload."""
import argparse
import hashlib
import io
import json
import math
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import threading
import time
import wave
import zipfile
from unittest.mock import patch


def seed(arc, desk):
    from recall_archive import DAY, now
    def wav(seconds):
        samples = b''.join(struct.pack('<h', int(550*math.sin(i*.06))) for i in range(1600))*int(seconds*10)
        result = io.BytesIO()
        with wave.open(result, 'wb') as f:
            f.setparams((1, 2, 16000, 0, 'NONE', 'not compressed')); f.writeframes(samples)
        return result.getvalue()
    stamp = now()-120000
    titles = [('A line for the morning', 'Poem line'), ('The echo in the stairwell', 'Connection'),
              ('A new word for returning', 'Forge word'), ('Notes for the blue cover', 'Project decision'),
              ('Four lines before the train', 'Poem line')]
    for index, (title, kind) in enumerate(titles):
        audio = wav(3+index*.7); uid=f'synthetic-clip-{index}'; digest=hashlib.sha256(audio).hexdigest()
        clip=dict(id=uid,title=title,startedAtMillis=stamp-index*DAY,durationMs=int((3+index*.7)*1000),
                  category=kind,reason='Three successive phrases had distinct rhyming endings.',transcript='Silver swallows trace the early sky.',
                  audio=f'audio/{uid}.wav',sha256=digest)
        payload=io.BytesIO()
        with zipfile.ZipFile(payload,'w') as z:
            z.writestr('index.json',json.dumps(dict(schema=desk.FORMAT,clips=[clip])));z.writestr(clip['audio'],audio)
        binary=payload.getvalue();manifest=dict(clip,kind='clip',bytes=len(binary),sha256=hashlib.sha256(binary).hexdigest());arc.begin(manifest);arc.put(uid,0,binary)
    aac = arc.root/'synthesis.aac'
    subprocess.run(['ffmpeg','-nostdin','-hide_banner','-loglevel','error','-f','lavfi','-i','sine=frequency=356:sample_rate=16000:duration=2','-af','apad=pad_dur=118','-c:a','aac','-f','adts','-y',str(aac)],check=True)
    data=aac.read_bytes();sig=hashlib.sha256(data).hexdigest()
    for uid,kind,title,body,time_offset,media in [('synthetic-segment','segment','Morning audio',data,60000,'audio/aac'),('synthetic-journal','journal','Morning journal',b'An anonymous test transcript.',40000,'text/plain')]:
        arc.begin(dict(id=uid,title=title,kind=kind,mediaType=media,sha256=hashlib.sha256(body).hexdigest(),bytes=len(body),startedAtMillis=stamp-time_offset,durationMs=120000 if kind=='segment' else 3000));arc.put(uid,0,body)
    return sig


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url',help='One-use, owner-approved receiver launch URL; use --url-file to avoid the process list.')
    parser.add_argument('--url-file',type=Path,help='Read the private launch URL from a file.')
    parser.add_argument('--expected-duration-ms',type=int,help='Check actual media timeline and a late excerpt at this duration.');parser.add_argument('--expected-id');parser.add_argument('--sha256');parser.add_argument('--chromium',default=shutil.which('chromium') or shutil.which('google-chrome'));parser.add_argument('--screenshots',type=Path)
    args=parser.parse_args()
    from playwright.sync_api import sync_playwright, expect
    source=Path(__file__).resolve().parent
    external=bool(args.url or args.url_file)
    if external and not (args.expected_id and args.sha256):parser.error('An external test needs --expected-id and --sha256.')
    if not external:
        import recall_desk as desk
        from recall_web import grant, web_server
    with tempfile.TemporaryDirectory() as tmp,sync_playwright() as p:
        root=Path(tmp);server=None
        if not external:
            isolated=patch.multiple(desk, ROOT=root, DB=root/'clips.sqlite3');isolated.start()
            arc=desk.archive();server=web_server(arc,source/'web',0);threading.Thread(target=server.serve_forever,daemon=True).start();url=grant(root,server.server_port)
        else:url=args.url_file.read_text().strip() if args.url_file else args.url
        browser=p.chromium.launch(executable_path=args.chromium,headless=True,args=['--no-sandbox','--disable-dev-shm-usage']);ctx=browser.new_context(viewport={'width':1366,'height':920},accept_downloads=True);page=ctx.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
        def nav(name):
            page.get_by_role('navigation').get_by_role('link',name=name,exact=True).click();expect(page.locator('#view')).to_have_attribute('aria-busy','false')
        def wait_audio(audio, predicate, limit=15):
            deadline=time.monotonic()+limit
            while not audio.evaluate(predicate):
                assert time.monotonic()<deadline, audio.evaluate('a=>({error:a.error?.message,ready:a.readyState,time:a.currentTime})')
                page.wait_for_timeout(75)
        def capture(name):
            if args.screenshots:
                args.screenshots.mkdir(parents=True,exist_ok=True);page.screenshot(path=str(args.screenshots/(name+'.png')),full_page=True)
        try:
            page.goto(url);expect(page.get_by_role('heading',name='Archive overview')).to_be_visible();expect(page.locator('#view')).to_have_attribute('aria-busy','false')
            if not external:
                expect(page.get_by_text('No selected clips yet.')).to_be_visible();capture('archive-empty');sig=seed(arc,desk);uid='synthetic-segment'
                page.get_by_role('button',name='Refresh archive').click();expect(page.get_by_text('A line for the morning')).to_be_visible();capture('archive-desktop')
                page.locator('#period').select_option('30');expect(page.locator('#view')).to_have_attribute('aria-busy','false');page.locator('#period').select_option('7')
                page.get_by_role('button',name='Daily totals').click();expect(page.get_by_role('heading',name='Daily totals')).to_be_visible();page.keyboard.press('Escape')
                page.get_by_text('A line for the morning',exact=True).click();expect(page.get_by_text('Recognized speech')).to_be_visible();capture('archive-detail')
                with page.expect_download() as wav_download:page.get_by_role('link',name='Download original').click()
                pcm=Path(wav_download.value.path()).read_bytes()[44:]
                page.get_by_text('Make an excerpt').click();page.get_by_label('Start (seconds)').fill('0.25');page.get_by_label('End (seconds)').fill('0.75')
                with page.expect_download() as wav_crop:page.get_by_role('button',name='Download excerpt').click()
                assert Path(wav_crop.value.path()).read_bytes()[44:]==pcm[8000:24000]
                page.get_by_role('button',name='Close item').click()
                # Test hosted controls against isolated responses; never handle a real pairing token.
                requests=[]
                def status(route):
                    old=route.fetch();values=old.json();values.update(hosted=True,phonePairing=True);route.fulfill(response=old,json=values)
                def pairing(route):
                    requests.append('pair');route.fulfill(json={'code':'TR1.SYNTHETIC-TEST-ONLY','origin':'https://example.invalid'})
                def qr(route):
                    requests.append('qr');route.fulfill(status=404,body='fixture')
                page.route('**/api/status',status);page.route('**/api/phone-pairing',pairing);page.route('**/api/phone-qr.png',qr)
                page.get_by_role('button',name='Refresh archive').click();page.get_by_role('button',name='Pair phone').click();assert not requests
                page.get_by_role('button',name='Reveal pairing code').click();expect(page.get_by_label('Private phone pairing code')).to_have_value('TR1.SYNTHETIC-TEST-ONLY')
                page.get_by_role('button',name='Copy pairing code').click();assert sorted(requests)==['pair','qr'];page.keyboard.press('Escape');expect(page.locator('.pair-code')).to_have_count(0)
                page.unroute('**/api/status');page.unroute('**/api/phone-pairing');page.unroute('**/api/phone-qr.png')
            else:sig,uid=args.sha256,args.expected_id
            nav('Archive');page.get_by_label('Item type',exact=True).select_option('segment');expect(page.locator('#view')).to_have_attribute('aria-busy','false');row=page.locator(f'button[data-item-id="{uid}"]');
            while row.count()==0 and page.get_by_role('button',name='Next',exact=True).count() and page.get_by_role('button',name='Next',exact=True).is_enabled():
                page.get_by_role('button',name='Next',exact=True).click();expect(page.locator('#view')).to_have_attribute('aria-busy','false')
            row.click();expect(page.get_by_role('dialog')).to_be_visible();audio=page.locator('audio');wait_audio(audio,"a=>a.readyState >= 1")
            page.get_by_label('Playback speed').select_option('1.5');assert audio.evaluate('a=>a.playbackRate')==1.5
            with page.expect_download() as download:page.get_by_role('link',name='Download original').click()
            original=Path(download.value.path()).read_bytes();assert hashlib.sha256(original).hexdigest()==sig
            page.get_by_text('Make an excerpt').click();duration=audio.evaluate('a=>a.duration');expected=args.expected_duration_ms if external else 120000
            if expected is not None:
                assert abs(duration-expected/1000)<.5, f'Incorrect playback timeline: {duration:.3f} s from a {expected/1000:.3f} s recording'
                assert abs(float(page.get_by_label('End (seconds)').input_value())-expected/1000)<.5
            start=max(0,expected/1000-4) if expected else 0
            part=round(min(1,(duration-start)/2),2);assert part>.01
            page.get_by_label('Start (seconds)').fill(str(start));page.get_by_label('End (seconds)').fill(str(round(start+part,2)));page.get_by_role('button',name='Preview range').click();wait_audio(audio,f"a=>a.paused && a.currentTime>={start+part-.1}",10)
            with page.expect_download() as excerpt:page.get_by_role('button',name='Download excerpt').click()
            with wave.open(str(excerpt.value.path()),'rb') as f:
                assert abs(f.getnframes()/f.getframerate()-part)<.002
                if not external:assert max(map(abs,struct.unpack('<'+'h'*(f.getnframes()*f.getnchannels()),f.readframes(f.getnframes()))))<10
            if expected is not None:print(f'PASS · {duration:.3f} s player duration, seek through {start+part:.2f} s and {part:.2f} s late excerpt.')
            if external:capture('archive-device-segment')
            page.get_by_role('button',name='Close item').click()
            if not external:
                page.get_by_label('Item type',exact=True).select_option('all');page.get_by_role('searchbox').fill('returning');expect(page.get_by_text('A new word for returning',exact=True)).to_be_visible();expect(page.get_by_text('A line for the morning',exact=True)).to_have_count(0)
                page.get_by_role('searchbox').fill('unmatched-zebra');expect(page.get_by_text('No matches.')).to_be_visible();page.get_by_role('searchbox').fill('');expect(page.get_by_text('Morning journal',exact=True)).to_be_visible();page.get_by_text('Morning journal',exact=True).click();expect(page.get_by_text('An anonymous test transcript.')).to_be_visible();page.keyboard.press('Escape');nav('Activity');expect(page.get_by_text('Unavailable',exact=True)).to_have_count(2);nav('Overview');page.set_viewport_size({'width':390,'height':844});capture('archive-mobile');assert page.evaluate('document.documentElement.scrollWidth<=innerWidth');nav('Archive');capture('archive-mobile-timeline');assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
            assert not errors,errors
            print('PASS · Private dashboard, archive row, original playback, speed, download SHA-256 and non-destructive WAV excerpt.');
            if not external:print('PASS · Empty state, search, zero matches, journal, activity, period changes and mobile overflow.')
        finally:
            browser.close()
            if server:server.shutdown();server.server_close();isolated.stop()

if __name__=='__main__':main()
