import struct, os, sys

def get_imports(path):
    with open(path, 'rb') as f:
        data = bytearray(f.read())
    pe_off = struct.unpack_from('<I', data, 0x3C)[0]
    machine = struct.unpack_from('<H', data, pe_off+4)[0]
    is64 = (machine == 0x8664)
    num_sec = struct.unpack_from('<H', data, pe_off+6)[0]
    opt_size = struct.unpack_from('<H', data, pe_off+20)[0]
    opt_off = pe_off + 24
    sec_table_off = opt_off + opt_size
    dd_off = opt_off + (112 if is64 else 96)
    import_rva = struct.unpack_from('<I', data, dd_off)[0]
    if import_rva == 0:
        return []
    def rva2off(rva):
        for i in range(num_sec):
            s = sec_table_off + i*40
            va = struct.unpack_from('<I', data, s+12)[0]
            vs = struct.unpack_from('<I', data, s+16)[0]
            ro = struct.unpack_from('<I', data, s+20)[0]
            if va <= rva < va+vs:
                return rva - va + ro
        return None
    dlls = []
    off = rva2off(import_rva)
    if off is None:
        return []
    while True:
        block = data[off:off+20]
        if block == b'\x00'*20:
            break
        name_rva = struct.unpack_from('<I', block, 12)[0]
        name_off = rva2off(name_rva)
        if name_off:
            raw = data[name_off:name_off+128]
            name = raw.split(b'\x00')[0].decode('latin-1')
            dlls.append(name)
        off += 20
    return dlls

base = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'bin', 'x64', 'Debug')
base = os.path.normpath(base)
print("Scanning:", base)
for dll in ['msvcp140.dll', 'onnxruntime.dll', 'vcruntime140.dll', 'vcruntime140_1.dll']:
    path = os.path.join(base, dll)
    try:
        imports = get_imports(path)
        print(dll + ' -> ' + (', '.join(imports) if imports else '(none)'))
    except Exception as e:
        print(dll + ' ERROR: ' + str(e))
