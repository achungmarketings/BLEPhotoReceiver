# python make_photo_header.py path/to/image.jpg
from pathlib import Path
import sys
source=Path(sys.argv[1]); data=source.read_bytes()
if data[:2]!=b'\xff\xd8' or data[-2:]!=b'\xff\xd9':
    raise SystemExit('Please supply a valid .jpg file (SOI/EOI required).')
out=Path(__file__).parent/'ESP32S3_BLE_Photo_Master'/'photo_data.h'
with out.open('w',encoding='utf-8') as f:
    f.write('#pragma once\n#include <Arduino.h>\nconst uint8_t photoBuffer[] PROGMEM = {\n')
    for i in range(0,len(data),16):
        f.write('  '+', '.join(f'0x{x:02X}' for x in data[i:i+16])+',\n')
    f.write('};\nconst size_t photoSize = sizeof(photoBuffer);\n')
print(f'Generated {out} from {source}: {len(data)} bytes')
