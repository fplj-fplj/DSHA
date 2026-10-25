"""Build portable Windows/WSL companions without copying private state."""
import argparse, hashlib, json, os, pathlib, subprocess, tarfile, zipfile, io

p=argparse.ArgumentParser(description=__doc__);p.add_argument('--go',default='go');p.add_argument('--out',required=True);a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[3];source=pathlib.Path(__file__).resolve().parent;out=pathlib.Path(a.out).resolve();out.mkdir(parents=True,exist_ok=True)
def run(args,env=None):
    result=subprocess.run(args,cwd=source,env=env or os.environ.copy(),stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    if result.returncode: raise RuntimeError(result.stdout)
    return result.stdout
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
plugin=root/'tools/remote/dsh-plugin';manifest=json.loads((plugin/'package.json').read_text(encoding='utf8'));tgz=out/f"{manifest['name']}-{manifest['version']}.tgz"
with tarfile.open(tgz,'w:gz') as archive:
    for name in ['package.json',*manifest['files']]:
        data=(plugin/name).read_bytes();entry=tarfile.TarInfo('package/'+name);entry.size=len(data);entry.mode=0o644;entry.mtime=0;archive.addfile(entry,io.BytesIO(data))
modules=run([a.go,'list','-m','-f','{{.Path}}|{{.Version}}|{{.Dir}}','all']).splitlines();licenses=[]
for line in modules:
    name,version,directory=line.split('|',2)
    if not directory: continue
    for candidate in sorted(pathlib.Path(directory).iterdir()):
        if candidate.is_file() and candidate.name.lower().startswith(('license','licence','copying','notice')):
            licenses.append((f"licenses/{name.replace('/','_')}@{version or 'local'}/{candidate.name}",candidate.read_bytes()))
license_path=root/'LICENSE'
if license_path.exists(): licenses.append(('LICENSE',license_path.read_bytes()))
artifacts=[]
for system in ['windows','linux']:
    name='dsha-connect.exe' if system=='windows' else 'dsha-connect'
    binary=out/f"dsha-connect-{system}-amd64{'.exe' if system=='windows' else ''}"
    env=os.environ.copy();env.update(GOOS=system,GOARCH='amd64',CGO_ENABLED='0')
    run([a.go,'build','-trimpath','-ldflags=-s -w','-o',str(binary),'.'],env)
    package=out/f'dsha-connect-{system}-amd64.zip'
    with zipfile.ZipFile(package,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
        entry=zipfile.ZipInfo(name);entry.external_attr=(0o100755<<16);entry.compress_type=zipfile.ZIP_DEFLATED;archive.writestr(entry,binary.read_bytes())
        archive.write(source/'README.md','README.md');archive.write(tgz,tgz.name)
        for filename,data in licenses:archive.writestr(filename,data)
    artifacts.append({'platform':system+'/amd64','binary':str(binary),'binarySHA256':sha(binary),'package':str(package),'packageSHA256':sha(package),'packageBytes':package.stat().st_size})
receipt={'status':'BUILT','go':run([a.go,'version']).strip(),'artifacts':artifacts,'plugin':{'path':str(tgz),'sha256':sha(tgz)},'includesPrivateState':False,'licenseFiles':len(licenses)}
(out/'packages.json').write_text(json.dumps(receipt,indent=2,ensure_ascii=False)+'\n',encoding='utf8');print(json.dumps(receipt,indent=2,ensure_ascii=False))
