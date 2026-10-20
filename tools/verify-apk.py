#!/usr/bin/env python3
"""校验 APK：Xposed API 不得被打进 dex；resources.arsc 必须 stored + 4 字节对齐。

用法: python3 tools/verify-apk.py path/to/app.apk
"""
import struct
import sys
import zipfile


def dex_defined_classes(data):
    string_ids_size, string_ids_off = struct.unpack_from("<II", data, 0x38)
    type_ids_size, type_ids_off = struct.unpack_from("<II", data, 0x40)
    class_defs_size, class_defs_off = struct.unpack_from("<II", data, 0x60)

    def string(idx):
        off = struct.unpack_from("<I", data, string_ids_off + idx * 4)[0]
        p = off
        while data[p] & 0x80:
            p += 1
        p += 1
        end = data.index(b"\x00", p)
        return data[p:end].decode("utf-8", "replace")

    def type_name(idx):
        return string(struct.unpack_from("<I", data, type_ids_off + idx * 4)[0])

    out = []
    for i in range(class_defs_size):
        class_idx = struct.unpack_from("<I", data, class_defs_off + i * 32)[0]
        out.append(type_name(class_idx))
    return out


def main(path):
    z = zipfile.ZipFile(path)
    ok = True

    dex = z.read("classes.dex")
    classes = dex_defined_classes(dex)
    print("dex: %d bytes, %d defined classes" % (len(dex), len(classes)))

    xposed = [c for c in classes if c.startswith("Lde/robv/")]
    if xposed:
        ok = False
        print("FAIL: Xposed API 被打进 dex（LSPosed 会拒绝加载）:", xposed[:5])
    else:
        print("OK  : dex 内没有任何 Xposed API 定义（compileOnly 生效）")

    names = z.namelist()
    if "assets/xposed_init" not in names:
        ok = False
        print("FAIL: 缺少 assets/xposed_init")
    else:
        print("OK  : assets/xposed_init ->", z.read("assets/xposed_init").decode().strip())

    arsc = [i for i in z.infolist() if i.filename == "resources.arsc"]
    if not arsc:
        ok = False
        print("FAIL: 缺少 resources.arsc")
    else:
        info = arsc[0]
        data_off = info.header_offset + 30 + len(info.filename) + len(info.extra)
        stored = info.compress_type == 0
        aligned = data_off % 4 == 0
        print("%s: resources.arsc stored=%s aligned4=%s (offset=%d)"
              % ("OK  " if stored and aligned else "FAIL", stored, aligned, data_off))
        ok = ok and stored and aligned

    print("RESULT:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "app/build/outputs/apk/debug/app-debug.apk"))
