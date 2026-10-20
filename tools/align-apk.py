#!/usr/bin/env python3
"""重写 APK：对 stored 条目做 4 字节对齐（等价 zipalign -p 4；本模块无 .so）。

Android 11+ 要求 resources.arsc 以 stored 方式存放且 4 字节对齐；
aapt2 link 不保证这一点（正常由 zipalign 处理），所以这里手工补。

用法: python3 tools/align-apk.py in.apk out.apk
"""
import struct
import sys
import zipfile
import zlib

ALIGN = 4
PAD_ID = 0xD935


def build(src, dst):
    zin = zipfile.ZipFile(src)
    out = open(dst, "wb")
    central = []
    offset = 0
    for info in zin.infolist():
        name = info.filename.encode("utf-8")
        data = zin.read(info.filename)
        crc = zlib.crc32(data) & 0xFFFFFFFF
        method = info.compress_type
        if method == zipfile.ZIP_DEFLATED:
            co = zlib.compressobj(9, zlib.DEFLATED, -15)
            payload = co.compress(data) + co.flush()
        else:
            payload = data

        base = offset + 30 + len(name)
        extra = b""
        if method == zipfile.ZIP_STORED:
            need = (-base) % ALIGN
            if need:
                total = need if need >= 4 else need + 4
                pad = total - 4
                extra = struct.pack("<HH", PAD_ID, pad) + b"\x00" * pad

        dt = info.date_time
        dosdate = ((dt[0] - 1980) << 9) | (dt[1] << 5) | dt[2]
        dostime = (dt[3] << 11) | (dt[4] << 5) | (dt[5] // 2)
        flags = 0x0800  # UTF-8 names

        out.write(struct.pack(
            "<IHHHHHIIIHH", 0x04034B50, 20, flags, method, dostime, dosdate,
            crc, len(payload), len(data), len(name), len(extra)))
        out.write(name)
        out.write(extra)
        out.write(payload)

        data_off = offset + 30 + len(name) + len(extra)
        central.append((name, method, dostime, dosdate, crc, len(payload),
                        len(data), extra, offset))
        offset = data_off + len(payload)

    cd_start = offset
    for (name, method, dostime, dosdate, crc, csize, usize, extra, lhoff) in central:
        out.write(struct.pack(
            "<IHHHHHHIIIHHHHHII", 0x02014B50, 20, 20, 0x0800, method, dostime,
            dosdate, crc, csize, usize, len(name), len(extra), 0, 0, 0, 0, lhoff))
        out.write(name)
        out.write(extra)
    cd_size = out.tell() - cd_start
    out.write(struct.pack("<IHHHHIIH", 0x06054B50, 0, 0, len(central), len(central),
                          cd_size, cd_start, 0))
    out.close()

    # 校验
    z = zipfile.ZipFile(dst)
    bad = z.testzip()
    if bad is not None:
        raise SystemExit("corrupt entry: " + bad)
    for info in z.infolist():
        if info.compress_type != zipfile.ZIP_STORED:
            continue
        off = info.header_offset + 30 + len(info.filename.encode("utf-8")) + len(info.extra)
        if off % ALIGN != 0:
            raise SystemExit("align failed: %s offset=%d" % (info.filename, off))
    print("aligned ok: %d entries" % len(z.infolist()))


if __name__ == "__main__":
    build(sys.argv[1], sys.argv[2])
