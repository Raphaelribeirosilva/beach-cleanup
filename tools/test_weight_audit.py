import unittest
from summarize_weight_audit import summarize


class WeightAuditTests(unittest.TestCase):
    def test_exact_sum_per_owner_and_missing_weights(self):
        records = [dict(collectionId=1, volunteerId=7, originalWeight='0.30000000000000004',
                        roundedWeight='0.300', deltaWeight='-0.00000000000000004'),
                   dict(collectionId=2, volunteerId=7, originalWeight='2.3455', roundedWeight='2.346', deltaWeight='0.0005'),
                   dict(collectionId=3, volunteerId=None, originalWeight=None, roundedWeight=None, deltaWeight=None)]
        report = summarize(records)
        self.assertEqual('2.646', report['total']['convertedKg'])
        self.assertEqual('0.00049999999999996', report['total']['differenceKg'])
        self.assertEqual(1, report['total']['missingWeights'])
        self.assertEqual('2.646', report['byOwner']['7']['convertedKg'])

    def test_extreme_legacy_values_do_not_lose_small_differences(self):
        report = summarize([
            dict(collectionId=1, originalWeight='1E+30', roundedWeight='1000000000000000000000000000000.000', deltaWeight='0.000'),
            dict(collectionId=2, originalWeight='1E-308', roundedWeight='0.000', deltaWeight='-1E-308')])
        self.assertEqual('-' + '0.' + '0' * 307 + '1', report['total']['differenceKg'])

    def test_inconsistent_or_duplicated_exports_are_rejected(self):
        row = dict(collectionId=1, originalWeight='1', roundedWeight='1.000', deltaWeight='0')
        with self.assertRaises(ValueError): summarize([row, row])
        with self.assertRaises(ValueError): summarize([{**row, 'deltaWeight': '0.1'}])
        with self.assertRaises(ValueError): summarize([{**row, 'originalWeight': 1.0}])
        with self.assertRaises(ValueError): summarize([{**row, 'originalWeight': 'inválido'}])


if __name__ == '__main__':
    unittest.main()
