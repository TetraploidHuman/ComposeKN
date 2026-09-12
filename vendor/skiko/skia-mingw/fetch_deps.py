import re, subprocess, os, sys, time
import os
DEPS = open(os.path.join(os.environ.get('SKIA_MINGW_WORK', '.'), 'skia/DEPS')).read()
pairs = re.findall(r'"(third_party/externals/[a-zA-Z0-9_.\-]+)"\s*:\s*"([^"]+@[0-9a-f]{40})"', DEPS)
want = set(sys.argv[1:])
sel = [(p, u.rsplit('@',1)) for p,u in pairs if p.split('/')[-1] in want]
base=os.path.join(os.environ.get('SKIA_MINGW_WORK','.'),'skia')
env = dict(os.environ, GIT_TERMINAL_PROMPT='0')

def fetch(item):
    path, (url, rev) = item
    full = os.path.join(base, path)
    os.makedirs(full, exist_ok=True)
    def run(cmd):
        return subprocess.run(cmd, cwd=full, env=env, capture_output=True, text=True)
    if os.path.exists(os.path.join(full, '.git')) and subprocess.run(['git','rev-parse','--verify','-q','HEAD'],cwd=full,capture_output=True).returncode==0:
        return f"SKIP {path} (已存在)"
    if not os.path.exists(os.path.join(full, '.git')):
        run(['git','init','-q']); run(['git','remote','add','origin',url])
    last=''
    for attempt in range(1,7):
        run(['git','remote','set-url','origin',url])
        r = run(['git','fetch','--depth','1','-q','origin',rev])
        if r.returncode==0:
            c = run(['git','checkout','-q','FETCH_HEAD'])
            if c.returncode==0: return f"OK   {path} (第{attempt}次)"
            last=c.stderr.strip()[:100]
        else:
            last=r.stderr.strip().splitlines()[-1][:100] if r.stderr.strip() else 'unknown'
        time.sleep(3)
    return f"FAIL {path}: {last}"
for item in sel:
    print(fetch(item), flush=True)
