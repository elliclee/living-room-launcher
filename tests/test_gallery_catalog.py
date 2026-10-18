from pathlib import Path
import hashlib,json,re,unittest
ROOT=Path(__file__).resolve().parents[1]

class GalleryCatalogTest(unittest.TestCase):
 def test_public_domain_art_and_distinct_nasa_usage(self):
  expected={'art':(12,8),'nasa':(5,2)}
  for kind,(count,bundled) in expected.items():
   with self.subTest(source=kind):
    raw=(ROOT/'assets'/kind/'catalog.json').read_bytes();self.assertLess(len(raw),65536)
    catalog=json.loads(raw);self.assertEqual(catalog['format_version'],1);works=catalog['works']
    self.assertEqual(len(works),count);self.assertEqual(len({w['id'] for w in works}),count)
    self.assertEqual(sum('asset' in w for w in works),bundled)
    for w in works:
     self.assertTrue(w['source_url'].startswith('https://'));self.assertTrue(w['display_title']);self.assertTrue(w['display_artist'])
     if kind=='art':
      self.assertIs(w['is_public_domain'],True);self.assertEqual(w['license'],'CC0-1.0')
      self.assertRegex(w['image_url'],r'^https://www\.artic\.edu/iiif/2/[0-9a-f-]{36}/full/!2560,1440/0/default\.jpg$')
     else:
      self.assertIs(w['rights_reviewed'],True);self.assertEqual(w['license'],'NASA-MEDIA-GUIDELINES')
      self.assertNotIn('is_public_domain',w)
      self.assertTrue(w['image_url'].startswith('https://images-assets.nasa.gov/image/'+w['id']+'/'))
     if 'asset' in w:
      self.assertEqual(w['asset'],kind+'/'+w['id']+'.jpg');data=(ROOT/'assets'/w['asset']).read_bytes()
      self.assertEqual(w['sha256'],hashlib.sha256(data).hexdigest());self.assertEqual(len(data),w['bytes']);self.assertLessEqual(len(data),8*1024*1024)
      self.assertTrue(data.startswith(b'\xff\xd8'))

 def test_no_unlisted_art_assets(self):
  for kind in ['art','nasa']:
   catalog=json.loads((ROOT/'assets'/kind/'catalog.json').read_text())
   listed={ROOT/'assets'/w['asset'] for w in catalog['works'] if 'asset' in w}
   self.assertEqual(set((ROOT/'assets'/kind).glob('*.jpg')),listed)

if __name__=='__main__':unittest.main()
