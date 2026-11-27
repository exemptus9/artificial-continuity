"""Measure complete local voice actions on controlled, synthetic speech using a specified checkout."""
import argparse
import multiprocessing
from pathlib import Path
from tempfile import TemporaryDirectory
import ctypes as C
import json
import subprocess
import numpy as np
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--libvosk',type=Path,required=True)
parser.add_argument('--model',type=Path,required=True)
parser.add_argument('--source',type=Path,default=Path(__file__).resolve().parents[1]/'android/src/org/totalrecall/listening')
parser.add_argument('--noise-filter',action='store_true')
parser.add_argument('--holdout',action='store_true')
parser.add_argument('--json',type=Path,help='Save the per-recording outcomes and both ASR readings')
args=parser.parse_args()
heldout=args.holdout
cases={
 'singleword':('Forge word',['hey recall keep that word']),
 'singleline':('Poem line',['lexi keep that line']),
 'trailing':('Forge word',['the paper lantern knows my name hey recall keep that word']),
 'twopart':('Poem line',['hey recall','keep that line']),
 'decision':('Project decision',['total recall keep that decision']),
 'incidental':('none',['the editor said keep that word for the next page']),
 'negated':('none',['please do not save that for me']),
 'quoted':('none',['yesterday we watched the movie total recall together']),
 'rhyme':('none',['the night is cold and leaves are falling']),
 'savelinesynonym':('Poem line',['hey recall save that line']),
 'quotedexample':('none',['the teacher said hey recall keep that word']),
 'nakedanchor':('none',['please recall that word from the instructions']),
}
conditions={'clear':(-22,None),'quiet':(-48,None),'moderate':(-30,12),'noise':(-35,3)}
voices=['slt','rms']
if heldout:
 cases={
  'write':('Poem line',['hey lexi keep that rhyme']),
  'word':('Forge word',['total recall save that word']),
  'choose':('Project decision',['hey recall save that decision']),
  'followup':('Forge word',['total recall','save that word']),
  'endofline':('Poem line',['the curtain lifts on a frozen lake hey recall keep that line']),
  'report':('none',['she was telling me to say hey lexi keep that rhyme']),
  'negation':('none',['do not tell recall to save that decision']),
  'discard':('none',['i never said to keep that word']),
  'film':('none',['we saw the film called total recall at the theater']),
  'normal':('none',['after dinner we walked across the bridge']),
 }
 voices=['awb','kal16']
 conditions={'clear':(-25,None),'quiet':(-45,None),'moderate':(-32,10),'noise':(-35,4)}
def run(operation, **kw):
    command = ['java', '-cp', str(HERE), 'org.totalrecall.listening.CommandReplay', operation]
    if args.noise_filter and operation == 'boost':
        command.append('denoise')
    return subprocess.run(command, check=True, capture_output=True, **kw)

def wav(phrase, voice):
    return np.frombuffer(subprocess.check_output(['ffmpeg', '-loglevel', 'error', '-f', 'lavfi', '-i', 'flite=text=' + phrase + ':voice=' + voice, '-ar', '16000', '-ac', '1', '-f', 's16le', '-']), dtype='<i2').astype(float)

def make():
    """Keep fixed raw PCM and independently transform the command and dictation channels."""
    paths = []
    for v in voices:
        for ci, (case, (expected, phrases)) in enumerate(cases.items()):
            parts = [wav(phrase, v) for phrase in phrases]
            if len(parts) > 1:
                parts.insert(1, np.zeros(16000))
            bare = np.concatenate(parts)
            for co, (level, snr) in conditions.items():
                base = HERE / f'{v}-{case}-{co}'
                name = str(base) + '.pcm'
                gain_name = str(base) + '.processed'
                if not Path(name).exists():
                    sig = bare * (32768 * 10 ** (level / 20) / np.sqrt(np.mean(bare * bare)))
                    sig = np.concatenate([np.zeros(4800), sig, np.zeros(24000)])
                    if snr:
                        t = np.arange(sig.size) / 16000
                        x = np.random.default_rng(73 + ci).normal(0, 0.67, sig.size) + 0.5 * np.sin(2 * np.pi * 110 * t)
                        if heldout:
                            y = np.convolve(np.random.default_rng(113 + ci).normal(size=sig.size + 9), np.ones(10) / 10, 'valid')
                            x = 0.55 * x + 2.0 * y * (1 + 0.33 * np.sin(2 * np.pi * 1.2 * t)) + 0.55 * np.sin(2 * np.pi * 240 * t) + 0.08 * np.sin(2 * np.pi * 720 * t)
                        x /= np.sqrt(np.mean(x * x))
                        sig += x * 32768 * 10 ** ((level - snr) / 20)
                    Path(name).write_bytes(np.round(sig).clip(-32768, 32767).astype('<i2').tobytes())
                paths.append((name, gain_name))
    run('boost', input=''.join((a + '\t' + b + '\n' for a, b in paths)), text=True)
    return paths

def init(grammar):
    """Load one unmodified model before forking a pristine native state for each clip."""
    global lib, new, newg, accept, res, partial, close, model
    lib = C.CDLL(str(args.libvosk.resolve()))

    def fn(name, ret, *args):
        f = getattr(lib, 'vosk_' + name)
        f.restype = ret
        f.argtypes = args
        return f
    p = C.c_void_p
    s = C.c_char_p
    n = C.c_int
    fn('set_log_level', None, n)(-1)
    model = fn('model_new', p, s)(str(args.model.resolve()).encode())
    assert model
    new = fn('recognizer_new', p, p, C.c_float)
    newg = fn('recognizer_new_grm', p, p, C.c_float, s)
    accept = fn('recognizer_accept_waveform', n, p, s, n)
    res = fn('recognizer_result', s, p)
    partial = fn('recognizer_partial_result', s, p)
    close = fn('recognizer_free', None, p)
    global gram
    gram = json.dumps(grammar).encode()

def decode(pair):
    orig, path = pair
    name = Path(orig).stem
    data = Path(path).read_bytes()
    commanddata = Path(path + '.cmd').read_bytes() if Path(path + '.cmd').exists() else data
    a = new(model, 16000)
    b = newg(model, 16000, gram)
    rows = []
    finals = []
    commands = []
    nextpartial = 0
    for start in range(0, len(data), 3200):
        chunk = data[start:start + 3200]
        cf = accept(b, commanddata[start:start + len(chunk)], len(chunk))
        candidate = json.loads(res(b))['text'] if cf else ''
        af = accept(a, chunk, len(chunk))
        words = json.loads(res(a))['text'] if af else ''
        ms = start / 32
        part = ''
        if not words and ms >= nextpartial:
            nextpartial = ms + 140
            part = json.loads(partial(a)).get('partial', '')
        rows.append(f'frame\t{words}\t{part}\t{candidate}')
        if words:
            finals.append(words)
        if candidate:
            commands.append(candidate)
    close(a)
    close(b)
    return (name, orig, rows, finals, commands)

def benchmark():
    sources = [args.source / (name + '.java') for name in ('SelectiveEngine', 'MicSignal', 'QuietSpeech')]
    if args.noise_filter:
        sources.append(args.source / 'CommandNoise.java')
    sources.append(Path(__file__).resolve().parent / 'host/CommandReplay.java')
    subprocess.run(['javac', '--release', '8', '-encoding', 'UTF-8', '-d', str(HERE), *sources], check=True)
    pairs = make()
    grammar = run('grammar', text=True).stdout.splitlines()
    init(grammar)
    with multiprocessing.get_context('fork').Pool(3, maxtasksperchild=1) as pool:
        results = pool.map(decode, pairs, chunksize=1)
    events = ''.join((f'start\t{name}\t{orig}\n' + '\n'.join(rows) + '\nend\n' for name, orig, rows, _, _ in results))
    outcomes = dict((line.split('\t', 1) for line in run('score', input=events, text=True).stdout.splitlines()))
    report = []
    for name, _, _, finals, commands in results:
        voice, case, condition = name.split('-')
        expected = cases[case][0]
        effects = outcomes[name].split('|')
        actions = [value for value in effects if value and (not value.startswith('wake@'))]
        correct = not actions if expected == 'none' else actions == ['clip=' + expected]
        report.append(dict(case=case, voice=voice, condition=condition, expected=expected, effects=effects, correct=correct, dictation=finals, restricted=commands))
    if args.json:
        args.json.write_text(json.dumps(report, indent=2) + '\n')
    for condition in conditions:
        rows = [r for r in report if r['condition'] == condition]
        targets = [r for r in rows if r['expected'] != 'none']
        negatives = [r for r in rows if r['expected'] == 'none']
        print(f"{condition}: {sum((r['correct'] for r in targets))}/{len(targets)} named saves, {sum((not r['correct'] for r in negatives))}/{len(negatives)} unwanted actions")
if __name__ == '__main__':
    with TemporaryDirectory() as temporary:
        HERE = Path(temporary)
        benchmark()
