"""Run portable user-level companions in isolated state directories."""
import argparse,json,pathlib,subprocess,tempfile,time,urllib.request,urllib.error,zipfile,os
p=argparse.ArgumentParser();p.add_argument('--packages',required=True);p.add_argument('--wsl',default='DSHA-build172');a=p.parse_args();base=pathlib.Path(a.packages).resolve()
def request(url,token=None,headers=None):
    values=headers or {}
    if token: values['Authorization']='Bearer '+token
    try:
        with urllib.request.urlopen(urllib.request.Request(url,headers=values),timeout=5) as r:return r.status,r.read()
    except urllib.error.HTTPError as e:return e.code,e.read()
state=pathlib.Path(tempfile.mkdtemp(prefix='windows-smoke-',dir=base.parent));exe=base/'dsha-connect-windows-amd64.exe'
child=subprocess.Popen([str(exe),'--state-dir',str(state)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,creationflags=subprocess.CREATE_NO_WINDOW)
try:
    until=time.monotonic()+15
    while not (state/'ipc.json').exists() and child.poll() is None and time.monotonic()<until:time.sleep(.05)
    ipc=json.loads((state/'ipc.json').read_text());url=ipc['url'];token=ipc['token']
    assert request(url+'/v1/status')[0]==401
    code,body=request(url+'/v1/status',token);assert code==200 and json.loads(body)['protocol']==1
    code,png=request(url+'/v1/qr.png',token);assert code==200 and png.startswith(b'\x89PNG\r\n\x1a\n')
    assert request(url+'/v1/status',token,{'Origin':'http://foreign.invalid'})[0]==403
    assert request(url+'/v1/status',token,{'Host':'foreign.invalid'})[0]==403
    code,ticket=request(url+'/v1/pair',token);value=json.loads(ticket);assert code==200 and value['v']==1 and len(value['addresses'])<=16
finally:child.terminate();child.wait(timeout=10)
binary=str(base/'dsha-connect-linux-amd64').replace('\\','/');binary='/mnt/'+binary[0].lower()+binary[2:]
linux_code='''import subprocess,tempfile,pathlib,time,json,urllib.request,os
state=pathlib.Path(tempfile.mkdtemp(prefix='dsha-connect-smoke-'));p=subprocess.Popen([BINARY,'--state-dir',str(state)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
try:
 end=time.monotonic()+15
 while not (state/'ipc.json').exists() and p.poll() is None and time.monotonic()<end:time.sleep(.05)
 ipc=json.loads((state/'ipc.json').read_text());r=urllib.request.urlopen(urllib.request.Request(ipc['url']+'/v1/status',headers={'Authorization':'Bearer '+ipc['token']}),timeout=5);assert json.load(r)['protocol']==1
 assert state.stat().st_mode&0o777==0o700
 assert (state/'state.json').stat().st_mode&0o777==0o600
 print('PASS actual WSL user-level server startup, authenticated IPC, 0700/0600')
finally:p.terminate();p.wait(timeout=10)
'''.replace('BINARY',repr(binary))
completed=subprocess.run(['wsl.exe','-d',a.wsl,'--exec','python3','-c',linux_code],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',timeout=40);assert completed.returncode==0,completed.stdout
for archive in base.glob('*.zip'):
    with zipfile.ZipFile(archive) as z: assert all(not name.endswith(('state.json','ipc.json')) for name in z.namelist())
result={'status':'PASS','windowsActualServer':True,'wslActualServer':True,'authenticatedIPC':True,'originAndHostRebindingRejected':True,'qrPNGGenerated':True,'windowsStateACL':'current-user-only separately verified by Go ACL test','wslStateModes':'0700/0600','privateStateInPackages':False,'phoneDeviceValidation':False}
(base.parent/'package-smoke.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
