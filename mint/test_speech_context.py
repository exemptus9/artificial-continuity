import unittest
from speech_context import correct, PersonalSpellings, checked_spellings
import json
from pathlib import Path
from tempfile import TemporaryDirectory

class ContextualSpellingTest(unittest.TestCase):
    def test_personal_terms_keep_boundaries_raw_wordplay_and_longest_match(self):
        with TemporaryDirectory() as directory:
            path=Path(directory)/"personal-spellings.json"
            path.write_text(json.dumps({"rhyme mosaic":"RhymeMosaic","rhyme":"Rhyme","rare":"x","x":"next"}))
            words=PersonalSpellings(path)
            self.assertEqual(words.correct("RHYME  MOSAIC then rare and x"),"RhymeMosaic then x and next")
            self.assertEqual(words.correct("rarely heard"),"rarely heard")
            self.assertEqual(words.correct("the word rhyme mosaic"),"the word rhyme mosaic")
            self.assertEqual(words.correct("please right it down at rhyme mosaic"),"please write it down at RhymeMosaic")
            path.unlink();words.next_check=0
            self.assertEqual(words.correct("rhyme mosaic"),"rhyme mosaic")
            with self.assertRaises(ValueError):checked_spellings({"":"anything"})
            with self.assertRaises(ValueError):checked_spellings({"line":"bad\\nline"})

    def test_precise_edits_and_literal_counterexamples(self):
        cases = {
            "please read the poem allowed":"please read the poem aloud",
            "she was allowed to read the poem":"she was allowed to read the poem",
            "please right it down":"please write it down",
            "turn right at the light":"turn right at the light",
            "i need a peace of paper":"I need a piece of paper",
            "i want peace in the world":"I want peace in the world",
            "wondering weather to go":"wondering whether to go",
            "the weather turned cold":"the weather turned cold",
            "the whether forecast":"the weather forecast",
            "could of been better":"could have been better",
            "apply the breaks":"apply the brakes",
            "she breaks the silence":"she breaks the silence",
            "because their trying to help":"because they're trying to help",
            "their going away worried me":"their going away worried me",
            "the word peace of paper appears twice":"the word peace of paper appears twice",
            "quote please read the poem allowed end quote":"quote please read the poem allowed end quote",
            "the word peace of paper is wordplay":"the word peace of paper is wordplay",
            "could you read my poem allowed and right it down":"could you read my poem aloud and write it down",
        }
        for heard,expected in cases.items():
            with self.subTest(heard=heard):
                self.assertEqual(correct(heard),expected)
                self.assertEqual(correct(expected),expected)

if __name__=="__main__":unittest.main()
