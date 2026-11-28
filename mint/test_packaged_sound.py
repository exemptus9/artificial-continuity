"""Run against an extracted amd64 package to exercise its native runtime and model."""
import argparse
from pathlib import Path
import sys


def verify(fixture):
    # Execute against the installation explicitly selected by the acceptance runner.
    sys.path.pop(0)
    import surroundings
    detector=surroundings.Detector()
    decisions=surroundings.Decisions()
    assert decisions.update(detector.scores(bytes(31200)))==""
    speech=fixture.read_bytes()[:31200]
    assert "Speech" in decisions.update(detector.scores(speech))
    timeline=surroundings.Timeline(600)
    timeline.add(speech[:1778]);timeline.add(speech[1778:]+speech)
    assert [(e["seconds"],e["quote"]) for e in timeline.events]==[(600,"Speech")]
    print("Packaged environmental sound model verified against silence and speech.")


if __name__=="__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--fixture",required=True,type=Path)
    verify(parser.parse_args().fixture)
