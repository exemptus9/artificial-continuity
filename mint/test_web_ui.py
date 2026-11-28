#!/usr/bin/env python3
"""Browser smoke: run against a disposable native receiver, or a synthetic phone's upload."""
from datetime import datetime,timezone
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
    line=datetime.fromtimestamp((stamp-40000)/1000,timezone.utc).strftime('%Y-%m-%d %H:%M:%S Z')
    journal=(line+'  An anonymous test transcript.\n  Originally heard · An anemone test transcript.\n').encode()
    data=aac.read_bytes();sig=hashlib.sha256(data).hexdigest()
    for uid,kind,title,body,time_offset,media in [('synthetic-segment','segment','Morning audio',data,60000,'audio/aac'),('synthetic-journal','journal','Morning journal',journal,40000,'text/plain')]:
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
        browser=p.chromium.launch(executable_path=args.chromium,headless=True,args=['--no-sandbox','--disable-dev-shm-usage']);ctx=browser.new_context(viewport={'width':1366,'height':920},timezone_id='America/New_York',accept_downloads=True);page=ctx.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
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
                page.get_by_role('searchbox').fill('unmatched-zebra');expect(page.get_by_text('No matches.')).to_be_visible();page.get_by_role('searchbox').fill('');expect(page.get_by_text('Morning journal',exact=True)).to_be_visible();page.get_by_text('Morning journal',exact=True).click();expect(page.get_by_text('An anonymous test transcript.')).to_be_visible();page.get_by_label('Find words in this journal').fill('anonymous')
                page.get_by_role('button',name='Compare changed wording').click()
                page.get_by_label('Find words in this journal').fill('anemone')
                expect(page.get_by_text('An anemone test transcript.', exact=True)).to_be_visible()
                page.get_by_role('button',name='Find audio here').click()
                audio=page.get_by_label('Original recording')
                wait_audio(audio,'a=>a.readyState>=1 && a.currentTime>=15 && a.currentTime<=18')
                page.get_by_role('button',name='Back to journal').click()
                expect(page.get_by_label('Find words in this journal')).to_have_value('anemone')
                expect(page.get_by_role('button',name='Show all lines')).to_have_attribute('aria-pressed','true')
                page.get_by_role('button',name='Keep line',exact=True).click()
                page.get_by_label('File this saved line').select_option('Poem draft')
                expect(page.get_by_text('Filed under Poem draft.',exact=True)).to_be_visible()
                page.get_by_role('link',name='Saved journal lines').click()
                expect(page.get_by_role('heading',name='Saved journal lines')).to_be_visible()
                prior_viewport=page.viewport_size
                page.set_viewport_size({'width':390,'height':844})
                expect(page.get_by_role('button',name='Open source line')).to_be_visible()
                assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
                capture('archive-saved-lines-mobile')
                page.set_viewport_size(prior_viewport)
                page.get_by_label('Search saved lines').fill('anemone')
                page.get_by_role('button',name='Search',exact=True).click()
                expect(page.get_by_text('An anonymous test transcript.',exact=True)).to_be_visible()
                page.get_by_role('button',name='Open source line').click()
                expect(page.get_by_label('Selected source line')).to_be_focused()
                source_link=page.get_by_role('link',name='Link to this line')
                link=source_link.get_attribute('href')
                assert link.startswith('/#journal/synthetic-journal/line/')
                source_link.click()
                page.reload()
                expect(page.get_by_label('Selected source line')).to_be_focused()
                page.get_by_role('button',name='Unsave line').click()
                expect(page.get_by_text('Removed from saved lines.',exact=True)).to_be_visible()
                page.keyboard.press('Escape');nav('Overview')
                page.get_by_role('button',name='Coined words',exact=True).click()
                expect(page.get_by_label('Clip label')).to_have_value('coined')
                expect(page.get_by_text('A new word for returning',exact=True)).to_be_visible()
                expect(page.get_by_text('Four lines before the train',exact=True)).to_have_count(0)
                page.get_by_label('Date started').fill('2000-01-01')
                expect(page.get_by_text('No items in this view.')).to_be_visible()
                page.get_by_role('button',name='Clear filters').click()
                page.get_by_role('searchbox').fill('anonymous')
                expect(page.get_by_text('Transcript ·',exact=False).first).to_be_visible()
                page.get_by_role('searchbox').fill('anonmyous test');expect(page.get_by_text('No matches.')).to_be_visible()
                page.get_by_role('button',name='Find similar spellings').click()
                expect(page.get_by_text('You typed “anonmyous” · saved as “anonymous”')).to_be_visible()
                page.get_by_role('button',name='Find audio at ',exact=False).click()
                expect(page.get_by_role('heading',name='Morning audio')).to_be_visible()
                wait_audio(page.get_by_label('Original recording'),'a=>a.readyState>=1 && a.currentTime>=15 && a.currentTime<=18')
                page.get_by_role('button',name='Back to journal').click()
                expect(page.get_by_label('Find words in this journal')).to_have_value('anonymous')
                page.keyboard.press('Escape')
                nav('Activity');expect(page.get_by_text('Unavailable',exact=True)).to_have_count(2)
                expect(page.get_by_role('heading',name='Saved clip patterns')).to_be_visible()
                verse=page.get_by_role('button',name='Review latest · Poetry and freestyle candidates')
                expect(verse.locator('..').get_by_text('2',exact=True)).to_be_visible()
                expect(page.get_by_role('heading',name='Busiest recorded day')).to_be_visible();capture('archive-patterns')
                verse.click();expect(page.get_by_role('heading',name='A line for the morning')).to_be_visible()
                page.get_by_role('button',name='Close item').click()
                nav('Overview');page.set_viewport_size({'width':390,'height':844});capture('archive-mobile');assert page.evaluate('document.documentElement.scrollWidth<=innerWidth');nav('Archive');capture('archive-mobile-timeline');assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
                nav('Activity');expect(page.get_by_role('button',name='Review latest · Coined-word candidates')).to_be_visible();capture('archive-mobile-patterns');assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
            if not external:
                nav('Archive')
                with arc.connection() as db:
                    marked=db.execute("SELECT started FROM archive_uploads WHERE id='synthetic-segment'").fetchone()[0]
                    cue=dict(quote="The synthetic lantern word <em data-test-source>markup</em>",seconds=23,stamp="0:23",label="Source excerpt")
                    db.execute("INSERT INTO recording_evidence VALUES(?,?,?,?,?)",('segment','synthetic-segment',0,'original',json.dumps([cue])))
                page.get_by_label('Search your archive').fill('lantern')
                evidence=page.get_by_role('button').filter(has_text='Recognized in audio · 0:23')
                expect(evidence).to_contain_text('<em data-test-source>markup</em>')
                expect(page.locator('[data-test-source]')).to_have_count(0)
                evidence.click()
                expect(page.get_by_text('Playback starts up to three seconds before this passage.')).to_be_visible()
                wait_audio(page.get_by_label('Original recording'),'a=>a.readyState>=1 && Math.abs(a.currentTime-20)<0.5')
                page.keyboard.press('Escape');page.get_by_role('button',name='Clear filters').click()
                date_str,time_str=page.evaluate("ms=>{const d=new Date(ms),pad=n=>String(n).padStart(2,'0');return [[d.getFullYear(),pad(d.getMonth()+1),pad(d.getDate())].join('-'),[pad(d.getHours()),pad(d.getMinutes())].join(':')]}",marked+80000)
                page.get_by_label('Date started').fill(date_str);page.get_by_text('Find by approximate time',exact=True).click()
                page.get_by_label('Time you remember').fill(time_str);page.get_by_label('Search window').select_option('5')
                page.get_by_role('button').filter(has_text='Morning audio').first.click()
                expect(page.get_by_text('Playback is cued near your remembered time',exact=False)).to_be_visible()
                page.keyboard.press('Escape')
                page.get_by_label('Date started').fill('2026-03-08');page.get_by_label('Time you remember').fill('02:30')
                expect(page.get_by_text('The clock skipped this time',exact=False)).to_be_visible()
                page.get_by_label('Date started').fill('2026-11-01');page.get_by_label('Time you remember').fill('01:30')
                both=page.get_by_label('Which occurrence?')
                expect(both.locator('option')).to_have_count(2)
                assert page.evaluate("localInstants('2026-11-01','01:30')[1]-localInstants('2026-11-01','01:30')[0]")==3600000
                both.select_option('1')
                page.evaluate("location.hash='clip/synthetic-clip-0'")
                expect(page.get_by_role('dialog').get_by_role('heading',name='A line for the morning')).to_be_visible()
                page.keyboard.press('Escape')
                nav('Review');expect(page.get_by_role('heading',name='Review inbox')).to_be_visible()
                layout=page.get_by_role('button',name='One at a time')
                layout.click();expect(layout).to_have_attribute('aria-pressed','true')
                expect(page.locator('.review-card')).to_have_count(1)
                first=page.locator('.review-title').inner_text()
                page.get_by_role('button',name='Next item',exact=True).click()
                expect(page.locator('.review-title')).not_to_have_text(first)
                page.get_by_role('button',name='Previous item',exact=True).click()
                expect(page.locator('.review-title')).to_have_text(first)
                page.locator('.review-card').get_by_role('button',name='Poem draft').click()
                expect(page.locator('.review-title')).not_to_have_text(first)
                page.get_by_role('button',name='Undo filing').click()
                expect(page.locator('.review-title')).to_have_text(first)
                page.reload();expect(layout).to_have_attribute('aria-pressed','true')
                layout.click();expect(layout).to_have_attribute('aria-pressed','false')
                card=page.locator('.review-card').first
                title=card.locator('.review-title').inner_text()
                card.get_by_role('button',name='Forge Lexicon').click()
                page.get_by_label('Review collection').select_option('label/Forge Lexicon')
                filed=page.locator('.review-card').filter(has_text=title)
                filed.locator('.review-title').click()
                page.get_by_label('Title or corrected word').fill('Sonicity')
                page.get_by_label('Draft, definition or next action').fill('A synthetic definition.')
                page.get_by_role('button',name='Save writing').click()
                expect(page.get_by_text('Writing saved.',exact=True)).to_be_visible()
                page.get_by_text('Delete from this archive',exact=True).click()
                erase=page.get_by_role('button',name='Delete this item')
                expect(erase).to_be_disabled()
                page.get_by_label('I want to delete this item.').check();erase.click()
                expect(page.get_by_text('Deleted from this archive.',exact=True)).to_be_visible()
                expect(page.locator('.review-card')).to_have_count(0)
                page.get_by_text('Inbox settings',exact=True).click()
                page.get_by_label('One-click categories (one per line)').fill('New project\nForge Lexicon')
                page.get_by_role('button',name='Save inbox settings').click()
                expect(page.get_by_text('Inbox settings saved.')).to_be_visible()
                page.get_by_label('Review collection').select_option('inbox')
                expect(page.locator('.review-card').first.get_by_role('button',name='New project')).to_be_visible()
                assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
            assert not errors,errors
            print('PASS · Private dashboard, archive row, original playback, speed, download SHA-256 and non-destructive WAV excerpt.');
            if not external:print('PASS · Empty state, search, zero matches, journal, activity, period changes and mobile overflow.')
        finally:
            browser.close()
            if server:server.shutdown();server.server_close();isolated.stop()

if __name__=='__main__':main()
