#!/usr/bin/env python3
"""Local-only integration checks against running Minecraft; uses a synthetic offline player.
Does not alter real users or send Minecraft chat messages. Requires the local admin password file.
"""
import gzip, hashlib, http.cookiejar, json, os, shutil, sqlite3, struct, subprocess, time, urllib.error, urllib.request, uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent

def docker(*args):return subprocess.check_output(['docker',*args],text=True).strip()
def nbt(t,value):
 if t in (1,2,3,4,5,6):return struct.pack({1:'>b',2:'>h',3:'>i',4:'>q',5:'>f',6:'>d'}[t],value)
 if t==8:
  value=value.encode();return struct.pack('>H',len(value))+value
 if t==10:return b''.join(bytes([kind])+nbt(8,key)+nbt(kind,val) for key,(kind,val) in value.items())+b'\0'
 if t==9:
  kind,values=value;return bytes([kind])+struct.pack('>i',len(values))+b''.join(nbt(kind,val) for val in values)
 if t==11:return struct.pack('>i',len(value))+b''.join(struct.pack('>i',v) for v in value)
 raise ValueError(t)
def item(slot,material,count,custom=False):
 value={'Slot':(1,slot),'id':(8,'minecraft:'+material),'count':(3,count)}
 if custom:value['components']=(10,{'minecraft:custom_data':(10,{'marker':(8,'preserve-unknown-components'),'number':(3,9876)})})
 return value

def main():
 os.chdir(ROOT)
 config=json.loads(docker('compose','-f','compose.local.yaml','config','--format','json'))
 origin=config['services']['panel']['environment']['PUBLIC_ORIGIN']
 if not origin.startswith('http://localhost:'):raise SystemExit('This smoke workflow targets the localhost development environment only')
 data=Path(next(v['source'] for v in config['services']['creative']['volumes'] if v['target']=='/data'))
 state=Path(next(v['source'] for v in config['services']['panel']['volumes'] if v['target']=='/state'))
 # The configured network's primary Paper world is world; abort rather than guess another folder.
 if not (data/'world/playerdata').is_dir():raise SystemExit('Expected local world/playerdata directory')
 identity=uuid.uuid4();target=data/'world/playerdata'/f'{identity}.dat'
 opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
 def api(route,method='GET',body=None,csrf='',expected=200):
  req=urllib.request.Request(origin+'/api/v1'+route,method=method,data=None if body is None else json.dumps(body).encode(),headers={'Origin':origin,'Content-Type':'application/json','X-CSRF-Token':csrf})
  try:r=opener.open(req,timeout=30)
  except urllib.error.HTTPError as e:r=e
  raw=r.read();assert r.code==expected,(route,r.code,raw.decode()[:500]);return json.loads(raw)
 password=Path('secrets/local/admin_password').read_text().strip()
 csrf=api('/login','POST',{'username':'admin','password':password})['csrf']
 root={'DataVersion':(3,4554),'UUID':(11,struct.unpack('>4i',identity.bytes)),'playerGameType':(3,0),'Health':(5,20.0),'foodLevel':(3,20),'XpLevel':(3,5),'Dimension':(8,'minecraft:overworld'),'Pos':(9,(6,[1.0,70.0,2.0])),'Inventory':(9,(10,[item(0,'diamond',3,True),item(103,'diamond_helmet',1),item(-106,'torch',8)])),'EnderItems':(9,(10,[item(0,'gold_ingot',7)])),'unknown-test-tag':(10,{'preserved':(8,'do-not-modify-this-tag'),'array':(11,[1,-2,3])})}
 with target.open('xb') as f:f.write(gzip.compress(b'\x0a\x00\x00'+nbt(10,root)))
 os.chmod(target,0o600)
 panel_id=docker('compose','-f','compose.local.yaml','ps','-q','panel');info=json.loads(docker('inspect',panel_id))[0]
 networks=[v for k,v in info['NetworkSettings']['Networks'].items() if k.endswith('_game')];assert len(networks)==1
 token=Path(config['secrets']['role_bridge_token']['file']).read_text().strip()
 req=urllib.request.Request('http://'+networks[0]['IPAddress']+':8083/bridge/register',method='POST',headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'},data=json.dumps({'server':'creative','players':[{'uuid':str(identity),'name':'InventorySmoke','lastSeen':0}]}).encode())
 try:
  with urllib.request.urlopen(req,timeout=10) as r:assert r.code==200
  for server in ['lobby','creative','survival']:
   result=api('/servers/'+server+'/game/state');assert len(result['worlds'])==3 and isinstance(result['whitelistEnabled'],bool)
  materials=api('/servers/creative/game/materials');assert 'minecraft:diamond' in materials
  path='/servers/creative/game/players/'+str(identity)
  view=api(path+'/inventory');assert not view['online'] and len(view['slots'])==41 and view['player']['gamemode']=='SURVIVAL'
  slots={s['slot']:s for s in view['slots']};assert slots[0]['amount']==3 and slots[0]['custom'] and slots[39]['material']=='minecraft:diamond_helmet' and slots[40]['amount']==8
  old=view['revision'];view=api(path+'/inventory','PUT',{'revision':old,'slot':0,'material':'minecraft:diamond','amount':5},csrf)
  assert view['slots'][0]['amount']==5 and view['slots'][0]['custom'] and 'preserve-unknown-components' in view['slots'][0]['details']
  api(path+'/inventory','PUT',{'revision':old,'slot':0,'material':'minecraft:stone','amount':1},csrf,409)
  api(path+'/inventory','PUT',{'revision':view['revision'],'slot':0,'material':'minecraft:diamond','amount':65},csrf,400)
  view=api(path+'/inventory','PUT',{'revision':view['revision'],'slot':40,'material':'minecraft:air','amount':0},csrf);assert view['slots'][40]['amount']==0
  ender=api(path+'/ender');assert len(ender['slots'])==27 and ender['slots'][0]['amount']==7
  ender=api(path+'/ender','PUT',{'revision':ender['revision'],'slot':0,'material':'minecraft:gold_ingot','amount':12},csrf);assert ender['slots'][0]['amount']==12
  api('/servers/creative/game/action','POST',{'action':'gamemode','uuid':str(identity),'value':'CREATIVE'},csrf)
  assert api(path+'/inventory')['player']['gamemode']=='CREATIVE'
  history=api(path+'/backups');assert len(history)>=4
  current=api(path+'/inventory')
  api('/servers/creative/game/action','POST',{'action':'restore-inventory','uuid':str(identity),'container':'inventory','revision':current['revision'],'backup':'../../ops.json'},csrf,400)
  api('/servers/creative/game/action','POST',{'action':'restore-inventory','uuid':str(identity),'container':'inventory','revision':current['revision'],'backup':history[-1]['file']},csrf)
  restored=api(path+'/inventory');assert restored['slots'][0]['amount']==3 and restored['player']['gamemode']=='CREATIVE'
  assert api(path+'/ender')['slots'][0]['amount']==12

  payload=gzip.decompress(target.read_bytes());assert b'preserve-unknown-components' in payload and b'do-not-modify-this-tag' in payload
  for _ in range(8):
   view=api(path+'/inventory');amount=4 if view['slots'][0]['amount']==5 else 5
   api(path+'/inventory','PUT',{'revision':view['revision'],'slot':0,'material':'minecraft:diamond','amount':amount},csrf)
  backups=data/'plugins/VampyuyuNetwork/player-backups'/str(identity);assert len(list(backups.glob('*.dat')))==10
  # Verify the audit contains the slot/UUID but no inventory component payload.
  activity=api('/activity?server=creative');assert any(str(identity) in entry['action'] for entry in activity);assert all('preserve-unknown-components' not in entry['action'] for entry in activity)
  api('/servers/creative/game/action','POST',{'action':'op','uuid':str(identity)},csrf,400)
  print('PASS: three real Minecraft bridges; saved inventories/armor/offhand/ender chest; metadata preservation; stack limits; stale-edit conflicts; offline game mode; bounded backups; private-data-free audit')
 finally:
  # Only our random synthetic UUID and its test backups are removed.
  target.unlink(missing_ok=True);target.with_suffix('.dat_old').unlink(missing_ok=True)
  backup=data/'plugins/VampyuyuNetwork/player-backups'/str(identity)
  if backup.exists():shutil.rmtree(backup)
  db=sqlite3.connect(state/'panel.db')
  for table in ['player_presence','player_operators','players']:db.execute('DELETE FROM '+table+' WHERE uuid=?',(str(identity),))
  db.commit();db.close();api('/logout','POST',{},csrf)
if __name__=='__main__':main()
