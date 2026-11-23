import ast
import base64
import gzip
from pathlib import Path
import types
import unittest
import zlib

class DecodeBudgetTest(unittest.TestCase):
    def setUp(self):
        tree=ast.parse((Path(__file__).resolve().parents[1]/'src/main/assets/embedded/engine.py').read_text())
        nodes=[n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name in {'bounded_content','flow_record','record','encode'}]
        self.ns=dict(zlib=zlib,MAX_BODY=2*1024*1024,base64=base64)
        exec(compile(ast.Module(body=nodes,type_ignores=[]),'real-engine-budget','exec'),self.ns)
    def decode(self,data,coding):
        return self.ns['bounded_content'](types.SimpleNamespace(raw_content=data,headers={'content-encoding':coding}))
    def test_normal_gzip_and_deflate(self):
        data=b'{"synthetic":true}'
        self.assertEqual(data,self.decode(gzip.compress(data),'gzip'))
        self.assertEqual(data,self.decode(zlib.compress(data),'deflate'))
    def test_compression_bomb_stops_at_output_limit(self):
        with self.assertRaises(ValueError):self.decode(gzip.compress(b'x'*(16*1024*1024)),'gzip')
    def test_truncated_and_concatenated_streams_rejected(self):
        zipped=gzip.compress(b'hello')
        for data in (zipped[:-1],zipped+zipped):
            with self.assertRaises(ValueError):self.decode(data,'gzip')
    def test_unknown_encoding_not_unboundedly_decoded(self):
        with self.assertRaises(ValueError):self.decode(b'body','br')
    def test_raw_body_limit(self):
        with self.assertRaises(ValueError):self.decode(b'x'*(2*1024*1024+1),'identity')
