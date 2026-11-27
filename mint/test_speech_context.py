import unittest
from speech_context import correct

class ContextualSpellingTest(unittest.TestCase):
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
            "the word peace of paper is wordplay":"the word peace of paper is wordplay",
            "could you read my poem allowed and right it down":"could you read my poem aloud and write it down",
        }
        for heard,expected in cases.items():
            with self.subTest(heard=heard):
                self.assertEqual(correct(heard),expected)
                self.assertEqual(correct(expected),expected)

if __name__=="__main__":unittest.main()
