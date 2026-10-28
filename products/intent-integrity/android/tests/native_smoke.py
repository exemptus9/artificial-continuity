"""Android 16 emulator checks. Separate test-only UI probe does NOT suppress the guard."""
import base64,json,pathlib,re,subprocess,time,xml.etree.ElementTree as ET
OUT=pathlib.Path('native-evidence');OUT.mkdir(exist_ok=True);checks=[]
def adb(*args,binary=False):return subprocess.check_output(['adb',*args],timeout=40,text=not binary)
def dump(name='latest'):
    result=adb('shell','am','instrument','-w','-r','com.intentintegrity.testprobe/.Snapshot')
    match=re.search(r'INSTRUMENTATION_RESULT: snapshot=([^\r\n]+)',result)
    if not match:raise AssertionError('Snapshot probe failed: '+result)
    value=base64.b64decode(match.group(1)).decode('utf-8');(OUT/(name+'.xml')).write_text(value);return value

def shot(name):(OUT/(name+'.png')).write_bytes(adb('exec-out','screencap','-p',binary=True))
def wait_text(text,name,timeout=12):
    # A newly opened dialog can be visible before the accessibility window list
    # includes it. Observe repeatedly; never repeat the consent action or bypass it.
    deadline=time.monotonic()+timeout
    while True:
        if text in dump(name):return True
        if time.monotonic()>=deadline:return False
        time.sleep(.4)
def check(ok,name):
    checks.append({'name':name,'passed':bool(ok)});(OUT/'checks.json').write_text(json.dumps({'environment':'Android API 36 emulator','fixture':'our own test app; no live social platform','probe':'UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES, separate test-only APK','checks':checks},indent=2))
    if not ok:shot('failure');raise AssertionError(name)
def tap(text,attempts=7):
    for attempt in range(attempts):
        root=ET.fromstring(dump())
        for n in root.iter('node'):
            if text.lower() in n.get('text','').lower() and n.get('enabled')=='true' and n.get('visible')=='true':
                b=list(map(int,re.findall(r'-?\d+',n.get('bounds',''))))
                if len(b)==4 and b[3]>b[1] and b[2]>b[0]:adb('shell','input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2));time.sleep(.7);return
        sizes=re.findall(r'(\d+)x(\d+)',adb('shell','wm','size'));w,h=map(int,sizes[-1]);adb('shell','input','swipe',str(w//2),str(int(h*.78)),str(w//2),str(int(h*.30)),'350');time.sleep(.4)
    raise AssertionError('Could not find control: '+text)
def launch(pkg):adb('shell','am','start','-W','-n',pkg+'/com.intentintegrity.app.MainActivity');time.sleep(1)
try:
    for path in pathlib.Path('release').glob('*.apk'):adb('install','-r',str(path))
    core='com.intentintegrity.app.core.dev';guard='com.intentintegrity.app.guard.dev';launch(core);check('You get the first move.' in dump('core-home'),'Core launches');shot('core-home')
    tap('Try the shield');check(wait_text('Feed held at the door.','browser-shield'),'Native browser gate shown without loading a feed');shot('browser-shield');adb('shell','input','keyevent','4');time.sleep(.5)
    tap('Privacy, compatibility');tap('Run offline diagnostics');check('7 / 7' in dump('diagnostics'),'Seven search routes pass on Android runtime');adb('shell','input','keyevent','4')
    launch(guard);tap('Protect installed apps');tap('Test feed app');tap('Review disclosure');check(wait_text('Accessibility access','consent'),'Prominent disclosure shown before service access');tap('I agree')
    component=guard+'/com.intentintegrity.app.IntentGuardService'
    # CI emulator provisioning AFTER the consent UI. This is not consumer installation guidance.
    adb('shell','settings','put','secure','enabled_accessibility_services',component);adb('shell','settings','put','secure','accessibility_enabled','1');time.sleep(3)
    (OUT/'accessibility-before.txt').write_text(adb('shell','dumpsys','accessibility'))
    adb('shell','am','start','-W','-n','com.intentintegrity.fixture/.MainActivity');time.sleep(2)
    (OUT/'windows-shield.txt').write_text(adb('shell','dumpsys','window','windows'))
    check('You opened Test feed app' in dump('native-shield'),'Guard covers selected fixture app');shot('native-shield')
    tap('Use native app');check('ALGORITHMIC FEED FIXTURE' in dump('native-pass') and 'You opened Test feed app' not in dump(),'Explicit native pass reveals the fixture')
    adb('shell','am','start','-a','android.settings.SETTINGS');time.sleep(1);check('You opened Test feed app' not in dump('settings-unblocked'),'System settings remain usable')
    adb('shell','am','start','-W','-n','com.intentintegrity.fixture/.MainActivity');time.sleep(62);check('You opened Test feed app' in dump('pass-expired'),'Native pass expires and shield returns');shot('pass-expired')
    tap('Pause protection');adb('shell','am','start','-W','-n','com.intentintegrity.fixture/.MainActivity');time.sleep(1);value=dump('paused');check('ALGORITHMIC FEED FIXTURE' in value and 'You opened Test feed app' not in value,'Pause restores ordinary access')
    print(json.dumps(checks,indent=2))
finally:
    for name,command in [('logcat.txt',('logcat','-d','-t','1500')),('accessibility-after.txt',('shell','dumpsys','accessibility'))]:
        try:(OUT/name).write_text(adb(*command))
        except Exception:pass
