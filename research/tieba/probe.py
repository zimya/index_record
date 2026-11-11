import json
import pathlib
import sys
import time

import frida

root = pathlib.Path(__file__).parent
device = frida.get_usb_device(timeout=10)
import subprocess

pid = int(subprocess.check_output(['adb', 'shell', 'pidof', 'com.baidu.tieba']).decode().split()[0])
session = device.attach(pid)
source = pathlib.Path(sys.argv[1]).read_text()
script = session.create_script(source)
def message(msg, data):
    payload = msg.get('payload', msg)
    if isinstance(payload, dict) and payload.get('capture'):
        token = str(time.time_ns())
        (root / (token + '.json')).write_text(json.dumps(payload, ensure_ascii=False, indent=2))
        print('CAPTURE', token, payload.get('url'), flush=True)
    else:
        print(json.dumps(payload, ensure_ascii=False), flush=True)
script.on('message', message)
script.load()
time.sleep(int(sys.argv[2]) if len(sys.argv) > 2 else 15)
session.detach()
