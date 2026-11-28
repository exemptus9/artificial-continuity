"""Five-second, in-memory calibration on Mint. This module does not write archive items."""
from difflib import SequenceMatcher
import fcntl
import io
import os
import queue
import re
import select
import stat
import subprocess
import threading
import time
import wave

import live_words
import mic_input
import situation
import voice_feedback

SECONDS = 5
TARGET = SECONDS * mic_input.RATE * 2
FIFO = "voice-check.fifo"
LINES = ("I wrote a line about the rain", "The silver river bends at night", "I want to remember this idea")


def capture_sample(root, source, use_stream, cancel, progress):
    """Collect bounded contiguous original PCM, closing the input immediately on cancellation."""
    pcm = bytearray()
    def append(data):
        pcm.extend(data[:TARGET-len(pcm)])
        progress(len(pcm)/TARGET)

    with (root/"voice-check.lock").open("a") as lock:
        try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:raise OSError("Another microphone check is already running.") from None
        if use_stream:
            path=root/FIFO
            if path.exists():
                if not stat.S_ISFIFO(path.lstat().st_mode):raise OSError("Practice audio pipe is unavailable.")
                path.unlink()
            os.mkfifo(path,0o600)
            try:
                with open(path,"rb",buffering=0,opener=lambda p,f:os.open(p,f|os.O_NONBLOCK|os.O_NOFOLLOW)) as pipe:
                    try:fcntl.fcntl(pipe.fileno(),fcntl.F_SETPIPE_SZ,65536)
                    except (AttributeError,OSError):pass
                    buffer=bytearray();expected=None;deadline=time.monotonic()+SECONDS+4
                    while len(pcm)<TARGET and not cancel.is_set():
                        if not (root/"enabled").exists():raise OSError("Laptop recording stopped during the check. Try again.")
                        if time.monotonic()>deadline:raise OSError("No continuous practice stream. Check the input. After an upgrade, sign out and back in to load the new recorder.")
                        if not select.select([pipe],[],[],.12)[0]:continue
                        chunk=pipe.read(65536)
                        if not chunk:
                            if expected is not None:raise OSError("Microphone stream ended. Run the check again.")
                            cancel.wait(.04);continue
                        buffer.extend(chunk)
                        while len(buffer)>=live_words.HEADER.size:
                            position,count=live_words.HEADER.unpack_from(buffer)
                            if count<=0 or count>4000 or count%2:raise OSError("Invalid practice audio frame.")
                            end=live_words.HEADER.size+count
                            if len(buffer)<end:break
                            if expected is not None and expected!=position:raise OSError("Audio gap during the check. Run it again.")
                            append(buffer[live_words.HEADER.size:end]);del buffer[:end];expected=position+count
            finally:path.unlink(missing_ok=True)
        else:
            args=["ffmpeg","-nostdin","-hide_banner","-v","error",*source,"-t",str(SECONDS),
                  "-ar",str(mic_input.RATE),"-ac","1","-f","s16le","pipe:1"]
            proc=subprocess.Popen(args,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
            try:
                deadline=time.monotonic()+SECONDS+4
                while len(pcm)<TARGET and not cancel.is_set():
                    if time.monotonic()>deadline:raise OSError("The microphone did not deliver a full sample. Check the input.")
                    if not select.select([proc.stdout],[],[],.12)[0]:continue
                    block=os.read(proc.stdout.fileno(),16384)
                    if not block:break
                    append(block)
            finally:
                proc.terminate()
                try:proc.wait(timeout=1)
                except subprocess.TimeoutExpired:proc.kill();proc.wait()
                proc.stdout.close()
    if cancel.is_set():raise InterruptedError("Check canceled.")
    if len(pcm)!=TARGET:raise OSError("No complete microphone sample. Check the input before trying again.")
    return bytes(pcm)


def compare(expected, heard):
    tokenize=lambda text:re.findall(r"[\w']+",text.casefold().replace("’","'"))
    wanted=tokenize(expected);actual=tokenize(heard)
    matched=0;missing=[];extra=[]
    for tag,a,b,c,d in SequenceMatcher(None,wanted,actual,autojunk=False).get_opcodes():
        if tag=="equal":matched+=b-a
        else:missing.extend(wanted[a:b]);extra.extend(actual[c:d])
    return matched,len(wanted),missing,extra


def recognize(pcm, gain, expected):
    import desk_speech
    with io.BytesIO(pcm) as stream:
        heard=" ".join(words for _,words in desk_speech.transcribe(stream,gain))
    return dict(heard=heard,match=compare(expected,heard),gain=gain)


def replay(root, pcm, cancel):
    """Share the existing live-decoder output guard; use a pipe without a replay file."""
    with (root/"spoken-feedback.lock").open("a") as lock:
        try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:raise OSError("Wait for the current spoken feedback to finish, then replay.") from None
        voice_feedback.hold(root,SECONDS+3)
        try:
            with io.BytesIO() as buffer:
                with wave.open(buffer,"wb") as wav:
                    wav.setparams((1,2,mic_input.RATE,0,"NONE",""));wav.writeframes(pcm)
                original=buffer.getvalue()
            with subprocess.Popen(["ffplay","-v","quiet","-nodisp","-autoexit","-f","wav","-i","pipe:0"],stdin=subprocess.PIPE,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL) as proc:
                try:
                    first=True;deadline=time.monotonic()+SECONDS+2
                    while not cancel.is_set() and time.monotonic()<deadline:
                        try:proc.communicate(original if first else None,timeout=.15);break
                        except subprocess.TimeoutExpired:first=False
                    if proc.poll() is None:proc.kill();proc.wait()
                    if proc.returncode and not cancel.is_set():raise OSError("Playback failed. Check the Mint audio output.")
                finally:
                    if proc.poll() is None:proc.kill();proc.wait()
        finally:voice_feedback.hold(root,.55)


def panel(parent,root,cfg,read_status):
    import tkinter as tk
    from tkinter import ttk
    window=tk.Toplevel(parent);window.title("Voice check");window.transient(parent)
    pane=ttk.Frame(window,padding=12);pane.pack(fill="both",expand=True)
    expected=tk.StringVar(value=LINES[0]);source=tk.StringVar();feedback=tk.StringVar()
    result=tk.StringVar();levels=tk.StringVar();step=tk.DoubleVar()
    ttk.Label(pane,text="Read a line in your usual voice",font=("Sans",12,"bold")).pack(anchor="w")
    prompt=ttk.Combobox(pane,values=LINES,textvariable=expected,state="readonly",width=36);prompt.pack(fill="x",pady=5)
    ttk.Label(pane,textvariable=source,wraplength=400).pack(anchor="w")
    ttk.Label(pane,textvariable=feedback,wraplength=400).pack(anchor="w",pady=(5,1))
    ttk.Progressbar(pane,maximum=1,variable=step).pack(fill="x",pady=4)
    ttk.Label(pane,textvariable=levels,wraplength=400).pack(anchor="w")
    ttk.Label(pane,textvariable=result,wraplength=400,justify="left").pack(anchor="w",pady=3)
    buttons=ttk.Frame(pane);buttons.pack(fill="x",pady=3)
    jobs=queue.SimpleQueue();cancel=threading.Event()
    sample=None;sample_line="";sample_gain=0;sample_report={};busy=False;timer=None;generation=0;answers={}
    def describe():
        c=cfg(root);active=(root/"enabled").exists()
        source.set(f"{c['source'].upper()} · {c['device']} · "+("Current recording continues. This check uses the same input." if active else "Recorder is off. The sample stays in this window."))
        return c,active
    def controls():
        record.configure(state="disabled" if busy else "normal")
        again.configure(state="normal" if sample and not busy else "disabled")
        test.configure(state="normal" if sample and not busy and len(answers)<3 else "disabled")
        prompt.configure(state="disabled" if busy else "readonly")
    def display():
        rows=[]
        for gain,data in answers.items():
            score,total,missing,extra=data["match"]
            rows.append(f"{'Current · ' if gain==sample_gain else ''}+{gain} dB · {score}/{total} words matched in order\nHeard: {data['heard'] or '(no words)'}")
            if gain==sample_gain and (missing or extra):
                if missing:rows.append("Missed: "+" ".join(missing))
                if extra:rows.append("Extra: "+" ".join(extra))
        result.set("\n".join(rows))
    def background(action,done):
        nonlocal busy
        busy=True;controls();this=generation
        def run():
            try:value=action()
            except (OSError,ValueError,RuntimeError,ImportError,subprocess.SubprocessError) as exc:value=exc
            jobs.put((this,done,value))
        threading.Thread(target=run,daemon=True).start()
    def poll():
        nonlocal timer,busy
        while not jobs.empty():
            version,fn,value=jobs.get()
            if version!=generation:continue
            if fn is None:step.set(value);continue
            busy=False
            if isinstance(value,Exception):feedback.set(str(value) or "The check could not finish.")
            else:fn(value)
            controls()
        timer=window.after(80,poll)
    def listen():
        nonlocal sample,sample_line,sample_gain,generation,answers
        c,active=describe()
        if active:
            status=read_status(root/"status.json",{})
            if (status.get("state")!="Recording" or not 0<=time.time()*1000-status.get("at",0)<6500 or not status.get("practiceStream")):
                feedback.set("Waiting for a compatible live input. Check the microphone, or stop recording to test it separately.");return
            if status.get("device")!=c["device"] or status.get("system")!=c["source"]:
                feedback.set("The selected microphone is changing. Wait for the recorder to reopen it, then try again.");return
        generation+=1;version=generation;sample=None;sample_line=expected.get();sample_gain=c["recognitionGain"];answers={}
        result.set("");levels.set("");step.set(0)
        feedback.set("Speak now · five seconds")
        def captured(pcm):
            nonlocal sample,sample_report
            sample=pcm;meter=mic_input.LevelWindow();meter.add(pcm);report=meter.snapshot();sample_report=report
            levels.set(mic_input.report(report)+" · "+(report["inputHint"] or "Five seconds received"))
            if report["peakDb"] is None:
                feedback.set("No microphone signal. Check mute, cable and selected input, then repeat.");return
            analyze([sample_gain])
        background(lambda:capture_sample(root,mic_input.arguments(c["source"],c["device"]),active,cancel,
            lambda n:jobs.put((version,None,n))),captured)
    def analyze(gains):
        pcm=sample;line=sample_line
        feedback.set("Checking this sample with offline speech recognition…")
        def run():
            results=[]
            for gain in gains:
                if cancel.is_set():break
                try:results.append(recognize(pcm,gain,line))
                except (ImportError,RuntimeError,OSError) as exc:
                    raise OSError("Offline recognition unavailable. Replay the original sample or check the installed English PocketSphinx model.") from exc
            return results
        def done(results):
            answers.update((r["gain"],r) for r in results);display()
            current=answers[sample_gain]["match"][0];total=answers[sample_gain]["match"][1]
            best=max(answers,key=lambda g:answers[g]["match"][0])
            if sample_report.get("clippedSamples",0) and "clipping" in sample_report.get("inputHint",""):
                advice="Lower the input level in Mint sound settings and repeat."
            elif answers[best]["match"][0]>current:
                advice=f"+{best} dB matched more words on this sample. Repeat once before changing the gain under Voice settings."
            elif current==total:advice="The current gain matched every prompted word on this take."
            elif len(answers)>1:advice="Additional gain did not improve this take. Replay it and check placement or background noise."
            elif sample_report.get("inputHint"):advice="Speak closer to the mic or compare gains on these same five seconds."
            else:advice="Replay any missed words, or compare alternate gains on the same audio."
            feedback.set("Compared with “"+sample_line+"”. "+advice)
        background(run,done)
    def play():
        pcm=sample
        feedback.set("Playing the original five seconds…")
        background(lambda:replay(root,pcm,cancel),lambda _:feedback.set("Playback finished."))
    record=ttk.Button(buttons,text="Start voice check",command=listen);record.pack(side="left")
    again=ttk.Button(buttons,text="Replay",command=play);again.pack(side="left",padx=3)
    test=ttk.Button(buttons,text="Compare gains",command=lambda:analyze([v for v in (0,6,12) if v not in answers]));test.pack(side="left")
    def close(event=None):
        nonlocal generation,sample
        if event is not None and event.widget is not window:return
        cancel.set();generation+=1;sample=None
        if timer is not None:window.after_cancel(timer)
    window.bind("<Destroy>",close,add="+")
    ttk.Button(buttons,text="Close",command=window.destroy).pack(side="right",padx=(4,0))
    describe();controls();poll();return window
