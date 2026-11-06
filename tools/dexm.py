#!/usr/bin/env python3
"""Minimal DEX method lister: prints class -> methods (name + proto) for classes
whose descriptor matches a regex. Pure stdlib, no deps.

用法 / usage:
    python3 dexm.py <classes.dex> [regex]

例: python3 dexm.py classes.dex 'globalactions/'
"""
import re
import struct
import sys


class Dex:
    def __init__(self, data):
        self.d = data
        (self.string_ids_size, self.string_ids_off,
         self.type_ids_size, self.type_ids_off,
         self.proto_ids_size, self.proto_ids_off,
         _field_ids_size, _field_ids_off,
         self.method_ids_size, self.method_ids_off,
         self.class_defs_size, self.class_defs_off) = struct.unpack_from('<12I', data, 56)
        self._str_cache = {}

    def u32(self, off):
        return struct.unpack_from('<I', self.d, off)[0]

    def u16(self, off):
        return struct.unpack_from('<H', self.d, off)[0]

    def uleb(self, off):
        result = 0
        shift = 0
        while True:
            b = self.d[off]
            off += 1
            result |= (b & 0x7f) << shift
            if b < 0x80:
                return result, off
            shift += 7

    def string(self, idx):
        s = self._str_cache.get(idx)
        if s is not None:
            return s
        off = self.u32(self.string_ids_off + idx * 4)
        _, off = self.uleb(off)          # utf16 length
        end = self.d.index(b'\x00', off)
        s = self.d[off:end].decode('utf-8', 'replace')
        self._str_cache[idx] = s
        return s

    def type_name(self, idx):
        return self.string(self.u32(self.type_ids_off + idx * 4))

    def proto(self, idx):
        off = self.proto_ids_off + idx * 12
        ret = self.u32(off + 4)
        params_off = self.u32(off + 8)
        params = []
        if params_off:
            n = self.u32(params_off)
            for i in range(n):
                params.append(self.type_name(self.u16(params_off + 4 + i * 2)))
        return self.type_name(ret), params

    def method(self, idx):
        off = self.method_ids_off + idx * 8
        cls = self.u16(off)
        proto = self.u16(off + 2)
        name = self.u32(off + 4)
        return self.type_name(cls), self.string(name), proto

    def classes(self):
        for i in range(self.class_defs_size):
            off = self.class_defs_off + i * 32
            cls_idx = self.u32(off)
            data_off = self.u32(off + 24)
            yield self.type_name(cls_idx), data_off

    def class_methods(self, data_off):
        out = []
        if not data_off:
            return out
        off = data_off
        sf, off = self.uleb(off)
        inf, off = self.uleb(off)
        dm, off = self.uleb(off)
        vm, off = self.uleb(off)
        for _ in range(sf + inf):
            _, off = self.uleb(off)
            _, off = self.uleb(off)
        for _ in range(dm + vm):
            diff, off = self.uleb(off)
            access, off = self.uleb(off)
            code, off = self.uleb(off)
            out.append((diff, access, code))
        # method index needs running accumulation across both lists
        return out


def methods(dex, data_off):
    res = []
    off = data_off
    sf, off = dex.uleb(off)
    inf, off = dex.uleb(off)
    dm, off = dex.uleb(off)
    vm, off = dex.uleb(off)
    for _ in range(sf + inf):
        _, off = dex.uleb(off)
        _, off = dex.uleb(off)
    idx = 0
    for count in (dm, vm):
        idx = 0
        for _ in range(count):
            diff, off = dex.uleb(off)
            access, off = dex.uleb(off)
            code, off = dex.uleb(off)
            idx += diff
            cls, name, proto = dex.method(idx)
            ret, params = dex.proto(proto)
            res.append((name, params, ret, access, code))
    return res


def main():
    path = sys.argv[1]
    pat = re.compile(sys.argv[2] if len(sys.argv) > 2 else '.')
    with open(path, 'rb') as f:
        dex = Dex(f.read())
    for cls, off in dex.classes():
        if not pat.search(cls):
            continue
        print('CLASS', cls)
        for name, params, ret, access, code in methods(dex, off):
            kind = 'native' if code == 0 else 'code'
            print('   %-44s (%s)%s  acc=0x%x %s' % (
                name, ''.join(params), ret, access, kind))


if __name__ == '__main__':
    main()
