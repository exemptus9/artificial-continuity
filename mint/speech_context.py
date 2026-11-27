"""Contextual spelling for displayed English speech. Voice commands consume the decoder's original."""
from functools import lru_cache
from pathlib import Path
import re

LITERAL = re.compile(r"\b(?:spell(?:ed|ing)?|homophone|pun intended|rhymes? with|play on words|wordplay|the word|put in quotes|quote unquote)\b",re.I)

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
