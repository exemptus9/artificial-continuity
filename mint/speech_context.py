"""Contextual spelling for displayed English speech. Voice commands consume the decoder's original."""
from functools import lru_cache
from pathlib import Path
import re
import json
import time

LITERAL = re.compile(r'\b(?:spell(?:ed|ing)?|homophones?|pun intended|rhymes? with|play on words|wordplay|the word|in quotes|quote(?: unquote)?|end quote)\b|[“”"]',re.I)

@lru_cache(maxsize=1)
def rules():
    path=Path(__file__).parent/"web"/"speech-context.tsv"
    return [(re.compile(pattern,re.I),word) for word,pattern in
            (line.split("\t",1) for line in path.read_text(encoding="utf-8").splitlines())]

def correct(words):
    if not words or LITERAL.search(words):
        return words
    edits={}
    for rule,spelling in rules():
        for match in rule.finditer(words):
            start,end=match.span(1)
            if match.group(1)!=spelling and not any(left<end and start<right for left,right in edits):
                edits[start,end]=spelling
    for (start,end),spelling in sorted(edits.items(),reverse=True):
        words=words[:start]+spelling+words[end:]
    return words


def checked_spellings(entries):
    if not isinstance(entries,dict) or len(entries)>120:
        raise ValueError("Keep up to 120 personal spellings.")
    checked={}
    for heard,written in entries.items():
        if not all(isinstance(value,str) and 0<len(value.strip())<=100 and
                   value.isprintable() for value in (heard,written)):
            raise ValueError("Enter a heard phrase and a spelling of up to 100 characters each.")
        heard=" ".join(heard.lower().split())
        if heard in checked:raise ValueError("This heard phrase already has a spelling.")
        checked[heard]=written.strip()
    return checked


class PersonalSpellings:
    """Read the laptop owner's literal phrase replacements without blocking microphone capture."""
    def __init__(self,path):
        self.path=path
        self.next_check=0
        self.version=None
        self.pattern=None
        self.words={}

    def refresh(self):
        when=time.monotonic()
        if when<self.next_check:return
        self.next_check=when+1
        try:
            stat=self.path.stat()
            version=(stat.st_ino,stat.st_mtime_ns,stat.st_size)
            if version==self.version:return
            if stat.st_size>128000:raise ValueError("Personal spellings file too large")
            words=checked_spellings(json.loads(self.path.read_text(encoding="utf-8")))
            patterns=[r"\s+".join(re.escape(part) for part in phrase.split()) for phrase in sorted(words,key=len,reverse=True)]
            self.pattern=re.compile(r"(?<!\w)(?:"+"|".join(patterns)+r")(?!\w)",re.I) if patterns else None
            self.words=words
            self.version=version
        except FileNotFoundError:
            self.pattern=None;self.words={};self.version=None
        except (ValueError,OSError):
            pass

    def correct(self,raw):
        if not raw or LITERAL.search(raw):return raw
        self.refresh()
        if self.pattern is None:return correct(raw)
        parts=[];start=0
        for match in self.pattern.finditer(raw):
            key=" ".join(match.group().lower().split())
            if key not in self.words:continue
            parts.extend((correct(raw[start:match.start()]),self.words[key]))
            start=match.end()
        parts.append(correct(raw[start:]))
        return "".join(parts)
