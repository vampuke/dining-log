#!/usr/bin/env python3
"""Read the actual APK binary manifest; no Android build or dependencies required."""
import json
from pathlib import Path
import struct
import sys
import zipfile


def manifest_attributes(data):
    if struct.unpack_from('<H', data)[0] != 3:
        raise ValueError('Expected compiled Android binary XML')
    strings = []
    attributes = {}
    offset = struct.unpack_from('<H', data, 2)[0]
    while offset < len(data):
        kind, header_size, chunk_size = struct.unpack_from('<HHI', data, offset)
        if chunk_size < header_size or offset + chunk_size > len(data):
            raise ValueError('Malformed binary XML chunk')
        if kind == 1:  # RES_STRING_POOL_TYPE
            count, _, flags, strings_start, _ = struct.unpack_from('<IIIII', data, offset + 8)
            offsets = struct.unpack_from('<' + 'I' * count, data, offset + header_size)

            def length8(position):
                first = data[position]
                if first & 128:
                    return ((first & 127) << 8) | data[position + 1], position + 2
                return first, position + 1

            def length16(position):
                first = struct.unpack_from('<H', data, position)[0]
                if first & 32768:
                    second = struct.unpack_from('<H', data, position + 2)[0]
                    return ((first & 32767) << 16) | second, position + 4
                return first, position + 2

            for relative in offsets:
                position = offset + strings_start + relative
                if flags & 256:  # UTF8_FLAG; character length precedes byte length.
                    _, position = length8(position)
                    length, position = length8(position)
                    strings.append(data[position:position + length].decode('utf-8'))
                else:
                    length, position = length16(position)
                    strings.append(data[position:position + length * 2].decode('utf-16le'))
        elif kind == 0x0102:  # RES_XML_START_ELEMENT_TYPE
            extension = offset + header_size
            _, name, attr_start, attr_size, attr_count = struct.unpack_from('<IIHHH', data, extension)
            for index in range(attr_count):
                position = extension + attr_start + attr_size * index
                _, attr_name, raw, _, _, value_type, value = struct.unpack_from('<IIIHBBI', data, position)
                decoded = strings[raw] if raw != 0xffffffff else strings[value] if value_type == 3 else value
                attributes[(strings[name], strings[attr_name])] = decoded
        offset += chunk_size
    return attributes


def main():
    path = Path(sys.argv[1])
    with zipfile.ZipFile(path) as apk:
        names = apk.namelist()
        if 'classes.dex' not in names or 'AndroidManifest.xml' not in names:
            raise ValueError('APK must contain classes.dex and AndroidManifest.xml')
        resources = sum(name.startswith('res/') for name in names)
        if not resources:
            raise ValueError('APK must contain Android res/ entries')
        with apk.open('classes.dex') as dex:
            if not dex.read(8).startswith(b'dex\n'):
                raise ValueError('classes.dex has no DEX header')
        attrs = manifest_attributes(apk.read('AndroidManifest.xml'))
    metadata = {
        'packageName': attrs.get(('manifest', 'package')),
        'minSdk': attrs.get(('uses-sdk', 'minSdkVersion')),
        'targetSdk': attrs.get(('uses-sdk', 'targetSdkVersion')),
        'versionName': attrs.get(('manifest', 'versionName')),
        'versionCode': attrs.get(('manifest', 'versionCode')),
        'resourceEntries': resources,
        'sizeBytes': path.stat().st_size,
    }
    print(json.dumps(metadata, ensure_ascii=False, indent=2))
    expected = {'packageName': 'top.alanvampuke.dininglog', 'minSdk': 26, 'targetSdk': 35}
    for field, value in expected.items():
        if metadata[field] != value:
            raise ValueError(f'APK {field}={metadata[field]!r}; expected {value!r}')


if __name__ == '__main__':
    main()
