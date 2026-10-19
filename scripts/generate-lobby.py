#!/usr/bin/env python3
"""Original Vampyuyu courtyard/palace. Generates a reviewed vanilla datapack; never applies it.
Run: python3 scripts/generate-lobby.py. Install only into a backed-up, empty Lobby world.
No entities, loot, command blocks or external build dependencies are used.
"""
from pathlib import Path
import json,math
out=Path(__file__).resolve().parents[1]/'infra/lobby-palace'
commands=[]; blocks={}
def box(x1,y1,z1,x2,y2,z2,material):
 x1,x2=sorted((int(x1),int(x2)));y1,y2=sorted((int(y1),int(y2)));z1,z2=sorted((int(z1),int(z2)))
 assert -52<=x1<=x2<=52 and 38<=y1<=y2<=115 and -52<=z1<=z2<=52
 # Every command stays below the normal vanilla fill limit.
 for y in range(y1,y2+1,4):
  upper=min(y2,y+3);commands.append(f'fill {x1} {y} {z1} {x2} {upper} {z2} minecraft:{material}')
 for x in range(x1,x2+1):
  for y in range(y1,y2+1):
   for z in range(z1,z2+1):
    if material=='air':blocks.pop((x,y,z),None)
    else:blocks[x,y,z]=material

def disk(radius,y,material):
 for x in range(-radius,radius+1):
  z=int(math.sqrt(radius*radius-x*x));box(x,y,-z,x,y,z,material)
def tower(x,z,height):
 box(x-4,64,z-4,x+4,height,z+4,'red_nether_bricks');box(x-3,66,z-3,x+3,height-1,z+3,'air')
 for dx in (-4,4):
  for dz in (-4,4):box(x+dx,65,z+dz,x+dx,height+1,z+dz,'quartz_pillar')
 for y in (65,71,height-1):
  box(x-5,y,z-5,x+5,y,z+5,'smooth_quartz');box(x-3,y+1,z-3,x+3,y+1,z+3,'air')
 for y in range(69,height-2):
  for side in (-4,4):box(x-1,y,z+side,x+1,y,z+side,'red_stained_glass');box(x+side,y,z-1,x+side,y,z+1,'red_stained_glass')
 for i in range(6):box(x-5+i,height+i,z-5+i,x+5-i,height+i,z+5-i,'nether_wart_block')
 box(x,height+6,z,x,height+7,z,'gold_block');box(x,height+8,z,x,height+9,z,'end_rod')

# Tapered floating island with an ivory/gold promenade and a wine inner ring.
for y,r in [(43,7),(47,13),(51,20),(55,27),(59,34),(62,40),(63,44)]:disk(r,y,'polished_deepslate')
# Diagonal stone ribs make the underside read as a sculpted island.
for x in range(-40,41,3):
 zmax=int(math.sqrt(max(0,1600-x*x)));depth=4+int((40-abs(x))/4)
 box(x,63-depth,-zmax,x,62,zmax,'deepslate_tiles')
disk(44,64,'polished_blackstone_bricks');disk(42,64,'gold_block');disk(41,64,'smooth_quartz');disk(31,64,'red_nether_bricks');disk(29,64,'smooth_quartz')
# Concentric courtyard tracery and crossing carpet avenues.
for x in range(-28,29,7):box(x,64,-28,x,64,28,'polished_diorite')
for z in range(-28,29,7):box(-28,64,z,28,64,z,'polished_diorite')
box(-3,64,-37,3,64,37,'red_concrete');box(-37,64,-3,37,64,3,'red_concrete')
for x in (-4,4):box(x,64,-37,x,64,37,'chiseled_quartz_block')
for z in (-4,4):box(-37,64,z,37,64,z,'chiseled_quartz_block')
disk(11,64,'gold_block');disk(10,64,'polished_blackstone');disk(9,64,'smooth_quartz')
# Original pixel bat floor inlay.
wing=['##.............##','####.........####','######.....######','#################','.###############.','..####.###.####..','...##..###..##...','.......###.......','........#........']
for iz,row in enumerate(wing):
 for ix,c in enumerate(row):
  if c=='#':box(ix-8,64,iz-4,ix-8,64,iz-4,'red_nether_bricks')
for x in (-1,1):box(x,64,-1,x,64,-1,'gold_block')
# Full perimeter balustrade, spaced gold finials.
for x in range(-44,45):
 z=int(math.sqrt(max(0,44*44-x*x)))
 for zz in set((z,-z)):box(x,65,zz,x,66,zz,'polished_blackstone_wall')
for angle in range(0,360,30):
 x=round(43*math.cos(math.radians(angle)));z=round(43*math.sin(math.radians(angle)))
 box(x,65,z,x,67,z,'quartz_pillar');box(x,68,z,x,68,z,'gold_block')
# Palace at the north end: an actual vaulted interior behind the front arcade.
box(-22,65,-35,22,84,-21,'red_nether_bricks');box(-20,66,-33,20,82,-23,'air');box(-22,65,-35,22,65,-21,'polished_blackstone_bricks')
for y in (66,82,84):box(-23,y,-36,23,y,-20,'smooth_quartz');box(-20,y,-33,20,y,-23,'air')
# Cross vault ribs and roof gables.
for x in (-18,-9,0,9,18):
 for dz in range(-6,7):
  yy=83-int(abs(dz)*.6);box(x,yy,-28+dz,x,yy,-28+dz,'quartz_pillar')
for step in range(9):box(-23,85+step,-36+step,23,85+step,-20-step,'nether_wart_block') if step<=7 else None
box(-23,93,-28,23,93,-28,'gold_block')
for x in (-21,-14,-7,7,14,21):
 box(x,66,-20,x,84,-20,'quartz_pillar');box(x-1,66,-20,x+1,67,-20,'smooth_quartz');box(x-1,83,-20,x+1,84,-20,'chiseled_quartz_block')
# Pointed entrance and side lancet windows.
box(-4,66,-22,4,75,-20,'air')
for level,width in [(76,3),(77,2),(78,1),(79,0)]:box(-width,level,-22,width,level,-20,'air')
for center in (-17,-10,10,17):
 box(center-1,69,-21,center+1,78,-21,'red_stained_glass');box(center,79,-21,center,80,-21,'red_stained_glass')
 box(center-2,68,-20,center+2,68,-20,'gold_block')
# Welcoming carpet and warm interior lighting.
box(-2,66,-33,2,66,-21,'red_carpet')
for x in (-18,-9,9,18):
 box(x,81,-28,x,82,-28,'iron_chain[axis=y]');box(x,80,-28,x,80,-28,'lantern[hanging=true]')
# Four spired towers; rear pair deliberately taller.
for x in (-28,28):
 for z in (-27,27):tower(x,z,89 if z<0 else 79)
# Brand lettering across the front, using blocks rather than floating text entities.
font={'V':['#.#','#.#','#.#','#.#','.#.'],'A':['.#.','#.#','###','#.#','#.#'],'M':['#.#','###','###','#.#','#.#'],'P':['##.','#.#','##.','#..','#..'],'Y':['#.#','#.#','.#.','.#.','.#.'],'U':['#.#','#.#','#.#','#.#','###']}
for n,char in enumerate('VAMPYUYU'):
 for row,line in enumerate(font[char]):
  for col,pixel in enumerate(line):
   if pixel=='#':box(-15+n*4+col,88-row,-19,-15+n*4+col,88-row,-19,'gold_block')
# Two world gates, designed as rose / amber portals without portal entities.
for sign in (-1,1):
 x=sign*32
 for z in (-5,5):box(x-1,65,z,x+1,72,z,'quartz_pillar');box(x-1,73,z,x+1,73,z,'gold_block')
 for y,width in [(73,4),(74,3),(75,2),(76,1),(77,0)]:
  for z in set((-width,width)):box(x-1,y,z,x+1,y,z,'red_nether_bricks')
 box(x,65,-3,x,71,3,'pink_stained_glass' if sign<0 else 'yellow_stained_glass')
 box(x-2,64,-4,x+2,64,4,'chiseled_quartz_block');box(x,64,-3,x,64,3,'gold_block')
 # Wall signs attach to the gate pillars; text is set by the lobby plugin.
 box(sign*30,67,-5,sign*30,67,-5,'oak_wall_sign[facing='+('east' if sign<0 else 'west')+']')
# Rose gardens, sealed reflective pools and custom blossom trees.
for sx in (-1,1):
 for sz in (-1,1):
  cx=sx*16;cz=sz*16
  box(cx-5,64,cz-5,cx+5,64,cz+5,'smooth_quartz');box(cx-4,65,cz-4,cx+4,65,cz+4,'moss_block')
  for dx in (-4,4):
   for dz in range(-4,5):box(cx+dx,66,cz+dz,cx+dx,66,cz+dz,'flowering_azalea_leaves[persistent=true]')
  for dz in (-4,4):
   for dx in range(-4,5):box(cx+dx,66,cz+dz,cx+dx,66,cz+dz,'flowering_azalea_leaves[persistent=true]')
  box(cx,66,cz,cx,72,cz,'stripped_dark_oak_log')
  for dx,dz,yy in [(-3,0,72),(3,0,73),(0,-3,73),(0,3,72),(0,0,75)]:
   box(cx+dx-2,yy,cz+dz-2,cx+dx+2,yy+1,cz+dz+2,'cherry_leaves[persistent=true]')
  box(cx-3,72,cz,cx+3,72,cz,'dark_oak_log[axis=x]');box(cx,73,cz-3,cx,73,cz+3,'dark_oak_log[axis=z]')
  box(cx,71,cz+3,cx,72,cz+3,'iron_chain[axis=y]');box(cx,70,cz+3,cx,70,cz+3,'lantern[hanging=true]')
for sx in (-1,1):
 cx=sx*17;box(cx-3,63,-4,cx+3,64,4,'smooth_quartz');box(cx-2,64,-3,cx+2,64,3,'water')
 for z in (-3,3):box(cx,65,z,cx,65,z,'lily_pad')
# Lamps and benches line the paths.
for x,z in [(-7,-10),(7,-10),(-7,10),(7,10),(-10,-7),(10,-7),(-10,7),(10,7),(-24,8),(24,8)]:
 box(x,65,z,x,65,z,'chiseled_quartz_block');box(x,66,z,x,69,z,'polished_blackstone_wall');box(x,70,z,x,70,z,'gold_block')
 for dx in (-1,1):box(x+dx,70,z,x+dx,70,z,'gold_block');box(x+dx,69,z,x+dx,69,z,'lantern[hanging=true]')
for z in (-10,10):
 for x in (-23,23):
  box(x-2,65,z,x+2,65,z,'dark_oak_stairs[facing='+('north' if z<0 else 'south')+']');box(x-3,65,z,x-3,66,z,'polished_blackstone_bricks');box(x+3,65,z,x+3,66,z,'polished_blackstone_bricks')
# Warm lighting upgrade: warm materials, low lanterns and concealed floor lights.
# Record a guarded patch too, so existing worlds can receive lighting without rebuilding.
lighting=[]
def glow(x,y,z,material):
 previous=blocks.get((x,y,z),'air')
 box(x,y,z,x,y,z,material)
 lighting.append(f'execute if block {x} {y} {z} minecraft:{previous} run setblock {x} {y} {z} minecraft:{material}')
# Pools reflect amber light; leaves conceal light without introducing entities.
for sx in (-1,1):
 cx=sx*17
 for z in (-2,2):glow(cx,63,z,'shroomlight')
 for sz in (-1,1):
  cx,cz=sx*16,sz*16
  for dx,dz in [(-3,-3),(-3,3),(3,-3),(3,3)]:
   glow(cx+dx,65,cz+dz,'shroomlight')
   glow(cx+dx,66,cz+dz,'flowering_azalea_leaves[persistent=true]')
  # Hanging lanterns beneath the blossom canopy.
  for dx in (-3,3):
   glow(cx+dx,71,cz,'iron_chain[axis=y]')
   glow(cx+dx,70,cz,'lantern[hanging=true]')
# Low bollards and concealed carpet lighting keep the courtyard softly legible.
for z in (-34,-27,-18,-10,10,18,27,34):
 for x in (-5,5):
  glow(x,65,z,'chiseled_quartz_block');glow(x,66,z,'lantern')
for z in (-16,-8,8,16,24,32):
 for x in (-2,2):
  glow(x,64,z,'shroomlight');glow(x,65,z,'red_carpet')
# Golden gate lights and candles on the bench armrests.
for sx in (-1,1):
 for z in (-5,5):glow(sx*32,74,z,'lantern')
for z in (-10,10):
 for x in (-26,-20,20,26):glow(x,67,z,'red_candle[candles=3,lit=true]')
# Lower chandeliers bring light down to the palace floor, with a warm amber canopy.
for cx in (-13,0,13):
 for y in range(76,81):glow(cx,y,-28,'iron_chain[axis=y]')
 glow(cx,75,-28,'gold_block')
 for dx,dz in [(-2,0),(-1,0),(1,0),(2,0),(0,-2),(0,-1),(0,1),(0,2)]:
  glow(cx+dx,75,-28+dz,'dark_oak_fence')
 for dx,dz in [(-2,0),(2,0),(0,-2),(0,2)]:glow(cx+dx,74,-28+dz,'lantern[hanging=true]')
for x in (-18,-9,9,18):
 glow(x,65,-25,'shroomlight');glow(x,66,-25,'red_carpet')
lighting += ['gamerule doDaylightCycle false','gamerule doWeatherCycle false','time set 12500','weather clear']
# Static invisible safety shell, in addition to the visible native world border.
for side in (-49,49):box(side,58,-49,side,112,49,'barrier');box(-49,58,side,49,112,side,'barrier')
commands += ['setworldspawn 0 65 12 180 0','worldborder center 0 0','worldborder set 100','gamerule doMobSpawning false','gamerule doMobLoot false','gamerule doEntityDrops false','gamerule mobGriefing false','gamerule doDaylightCycle false','gamerule doWeatherCycle false','gamerule doFireTick false','time set 12500','weather clear','data modify storage vampyuyu:lobby built set value 1b']
func=out/'data/vampyuyu/function';func.mkdir(parents=True,exist_ok=True)
(func/'build_once.mcfunction').write_text('# Original Vampyuyu Sanctum · reproducible block-only build\n'+'\n'.join(commands)+'\n')
(func/'warm_lights.mcfunction').write_text('# Guarded lighting upgrade for an existing Sanctum; never rebuilds terrain.\n'+'\n'.join(lighting)+'\n')
(func/'install.mcfunction').write_text('execute unless data storage vampyuyu:lobby built run function vampyuyu:build_once\n')
tag=out/'data/minecraft/tags/function';tag.mkdir(parents=True,exist_ok=True);(tag/'load.json').write_text(json.dumps({'values':['vampyuyu:install']},indent=2)+'\n')
(out/'pack.mcmeta').write_text(json.dumps({'pack':{'description':'Vampyuyu Sanctum · burgundy, ivory & gold lobby','min_format':[88,0],'max_format':[88,0]}},indent=2)+'\n')
# Preview source excludes invisible barrier blocks, using actual final voxel positions.
preview=[[*p,m] for p,m in blocks.items() if m!='barrier']
Path('/tmp/vampyuyu-lobby-voxels.json').write_text(json.dumps(preview,separators=(',',':')))
print(f'{len(commands)} bounded commands; {len(preview)} visible blocks; footprint 99×99. Datapack: {out}')
