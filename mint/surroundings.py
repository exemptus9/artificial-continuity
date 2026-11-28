"""On-device sound suggestions from raw mono 16 kHz PCM, independent of speech."""
from pathlib import Path
import queue
import sys
import threading
import time

WINDOW=15600
ASSETS=Path(__file__).resolve().parent/"sound-model"
if not ASSETS.is_dir():
    ASSETS=Path("/usr/share/total-recall-listening/sound-model")


def groups():
    rows=[]
    for line in (Path(__file__).parent/"web"/"sound-groups.tsv").read_text().splitlines():
        name,spec=line.split("\t")
        ids=[]
        for item in spec.split(","):
            ends=item.split("-");ids.extend(range(int(ends[0]),int(ends[-1])+1))
        rows.append((name,ids))
    return rows


class Detector:
    def __init__(self):
        import numpy as np
        runtime=ASSETS/("cp"+str(sys.version_info.major)+str(sys.version_info.minor))
        if runtime.is_dir() and str(runtime) not in sys.path:sys.path.insert(0,str(runtime))
        from ai_edge_litert.interpreter import Interpreter
        self.model=Interpreter(model_path=str(ASSETS/"yamnet.tflite"),num_threads=1)
        self.model.allocate_tensors()
        self.input=self.model.get_input_details()[0]["index"]
        self.output=self.model.get_output_details()[0]["index"]
        self.groups=groups()
        self.np=np

    def scores(self,pcm):
        samples=self.np.frombuffer(pcm,dtype="<i2").astype(self.np.float32)/32768
        self.model.set_tensor(self.input,samples)
        self.model.invoke()
        scores=self.model.get_tensor(self.output)[0]
        return {name:float(max(scores[ids])) for name,ids in self.groups}


class Decisions:
    def __init__(self):
        self.last=set()

    def update(self,scores):
        eligible={k for k,v in scores.items() if v>=.36}
        names=sorted((k for k in eligible if scores[k]>=.70 or k in self.last),key=scores.get,reverse=True)
        self.last=eligible
        return " + ".join(names[:2])


class LiveSounds:
    def __init__(self):
        self.buffer=bytearray();self.work=queue.Queue(maxsize=1)
        self.finished=threading.Event();self.generation=0;self.sequence=0
        self.result=None
        self.worker=threading.Thread(target=self.run,daemon=True);self.worker.start()

    def reset(self):
        self.generation+=1;self.buffer.clear();self.result=None

    def feed(self,pcm):
        self.buffer.extend(pcm)
        while len(self.buffer)>=2*WINDOW:
            frame=bytes(self.buffer[:2*WINDOW]);del self.buffer[:2*WINDOW]
            self.sequence+=1
            try:self.work.put_nowait((self.generation,self.sequence,time.monotonic(),frame))
            except queue.Full:pass

    def run(self):
        try:model=Detector()
        except (ImportError,OSError,ValueError,RuntimeError):
            self.unavailable=True;return
        last=None;decisions=Decisions()
        while not self.finished.is_set():
            try:g,n,at,pcm=self.work.get(timeout=.4)
            except queue.Empty:continue
            if g!=self.generation or time.monotonic()-at>2:continue
            if last!=(g,n-1):decisions=Decisions()
            try:label=decisions.update(model.scores(pcm))
            except (OSError,RuntimeError,ValueError):
                self.unavailable=True;return
            last=(g,n);self.result=(g,at,label)

    def label(self):
        if getattr(self,"unavailable",False):return "Sound recognition unavailable"
        result=self.result
        if result and result[0]==self.generation and time.monotonic()-result[1]<3:
            return "Possible · "+result[2] if result[2] else "No clear sound match"
        return "Listening for a sound pattern"

    def close(self):
        self.finished.set();self.reset()
        self.worker.join(timeout=2)


class Timeline:
    def __init__(self,offset):
        self.decisions=Decisions();self.buffer=bytearray();self.samples=0
        self.offset=offset;self.events=[];self.last=""
        try:self.detector=Detector()
        except (ValueError,OSError,RuntimeError,ImportError):self.detector=None

    def add(self,block):
        if self.detector is None:return
        self.buffer.extend(block)
        while len(self.buffer)>=WINDOW*2:
            frame=bytes(self.buffer[:WINDOW*2]);del self.buffer[:WINDOW*2]
            try:label=self.decisions.update(self.detector.scores(frame))
            except (RuntimeError,ValueError,OSError):
                self.detector=None;return
            at=self.offset+self.samples/16000;self.samples+=WINDOW
            if label and label!=self.last:
                self.events.append(dict(label="Possible sound",quote=label,seconds=at,stamp=f"{int(at)//60}:{int(at)%60:02d}",sound=True))
            self.last=label
