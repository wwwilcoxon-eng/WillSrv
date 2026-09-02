package dev.willsrv.physics;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Fisica de puertas/trapdoors/jarrones estilo Garry's Mod con BlockDisplay + Interaction hitbox.
 *
 * Cambios 2026-09-01 v3: cobre 45t, jarrones shatter>4, rebote real en paredes, textura completa (2 displays para puerta), sin minimizar en reposo, hitbox Interaction, pickup tras 5s.
 */
public final class PhysicsManager {

    private static final int SUSTAIN_TICKS_WOOD = 50;
    private static final int SUSTAIN_TICKS_COPPER = 45;
    private static final int SUSTAIN_TICKS_IRON = 80;
    private static final int SUSTAIN_TICKS_VASE = 15; // jarrones/macetas 1.5s caminata (con throttle 2t =15 almacenados)
    private static final int SUSTAIN_TICKS_HEAD = 20; // cabeza 1s corriendo
    private static final double HEAD_WEIGHT = 5.5; // realmente pesada
    private static final int SUSTAIN_TICKS_GLIDE = 10;
    private static final int SUSTAIN_TICKS_FLY = 10;
    private static final double RUN_MIN_SPEED = 0.14;
    private static final double GLIDE_MIN_SPEED = 0.18;
    private static final double FALL_MIN_SPEED = 0.45;
    private static final double FLY_MIN_SPEED = 0.14;

    private static final double GRAVITY = 0.05;
    private static final double AIR_DRAG = 0.99;
    private static final double GROUND_FRICTION = 0.78;
    private static final double RESTITUTION = 0.52;
    private static final int MAX_BOUNCES = 4;
    private static final int BODY_TTL_TICKS = 20 * 23;
    private static final int MAX_BODIES = 48;
    private static final int PICKUP_DELAY_TICKS = 20 * 5; // 5s antes de poder recoger

    private static final double IRON_IMPULSE = 1.9;

    private final JavaPlugin plugin;
    private final Random random = new Random();
    private final NamespacedKey key;
    private final List<DoorBody> bodies = new ArrayList<>();
    private final Map<UUID, Integer> sustain = new HashMap<>();
    private final Map<UUID, Long> lastMoveTick = new HashMap<>();
    private BukkitTask stepTask;

    public PhysicsManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "physics_door");
    }

    void start() {
        Bukkit.getPluginManager().registerEvents(new PListener(), plugin);
        stepTask = Bukkit.getScheduler().runTaskTimer(plugin, this::stepPhysics, 1L, 1L);
    }

    void stop() {
        if (stepTask != null) stepTask.cancel();
        stepTask = null;
        for (DoorBody b : new ArrayList<>(bodies)) removeBodyAndEntities(b);
        bodies.clear();
        sustain.clear();
    }

    private void onMove(PlayerMoveEvent e) {
        // No throttle agresivo para jarrones: registra también caminata lenta
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (p.getGameMode() == GameMode.SPECTATOR) { sustain.remove(id); return; }
        // Ignora solo giros de cabeza sin movimiento
        if (e.getFrom().getX()==e.getTo().getX() && e.getFrom().getZ()==e.getTo().getZ() && e.getFrom().getY()==e.getTo().getY()) return;
        double dx = e.getTo().getX() - e.getFrom().getX();
        double dy = e.getTo().getY() - e.getFrom().getY();
        double dz = e.getTo().getZ() - e.getFrom().getZ();
        double horiz = Math.hypot(dx, dz);
        boolean running = p.isSprinting() && horiz >= RUN_MIN_SPEED;
        boolean walking = horiz >= 0.08;
        boolean gliding = p.isGliding() && horiz >= GLIDE_MIN_SPEED;
        boolean flying = p.isFlying() && horiz >= FLY_MIN_SPEED;
        boolean falling = -dy >= FALL_MIN_SPEED;
        // Sustain cuenta caminata también para jarrones (más livianos)
        if (running || walking || gliding || flying) sustain.merge(id, 1, Integer::sum); else sustain.remove(id);
        if (falling) { attemptBreak(p, new Vector(dx,0,dz), horiz, true, gliding||flying); return; }
        if (!running && !gliding && !flying) return;
        Integer ticks = sustain.get(id);
        if (ticks==null) return;
        if ((gliding||flying) && ticks>=SUSTAIN_TICKS_GLIDE) { attemptBreak(p,new Vector(dx,0,dz),horiz,false,true); return; }
        if (running && ticks < SUSTAIN_TICKS_WOOD) {
            // Permite empujar hitbox exacta aunque no rompa (contacto)
            pushNearbyBodies(p, new Vector(dx,0,dz), horiz);
            return;
        }
        // Antes de romper, también empuja si toca
        pushNearbyBodies(p, new Vector(dx,0,dz), horiz);
        attemptBreak(p,new Vector(dx,0,dz),horiz,false,false);
    }

    private void attemptBreak(Player p, Vector moveDir, double horiz, boolean isFall, boolean isGlideFly) {
        if (moveDir.lengthSquared()<1e-4) moveDir=p.getEyeLocation().getDirection().setY(0);
        if (moveDir.lengthSquared()<1e-4) return;
        Vector dir=moveDir.clone().normalize();
        Location feet=p.getLocation().clone(); feet.setY(Math.floor(feet.getY())+0.5);
        double reach=isFall?0.4:1.3; double step=0.2;
        for(double s=0;s<=reach;s+=step){
            Location probe=feet.clone().add(dir.getX()*s,0,dir.getZ()*s);
            for(int lvl=0;lvl<2;lvl++){
                Block b=probe.clone().add(0,lvl,0).getBlock();
                if(b.getType()==Material.AIR) continue;
                if(isDoor(b.getType())){ tryBreakDoor(p,b,dir,horiz,isFall,isGlideFly,probe.getX(),probe.getZ()); return; }
                if(isTrapDoor(b.getType())){ tryBreakTrapdoor(p,b,dir,horiz,isFall,isGlideFly,probe.getX(),probe.getZ()); return; }
                if(isVase(b.getType())){ tryBreakVase(p,b,dir,horiz,isFall,isGlideFly,probe.getX(),probe.getZ()); return; }
                if(isHead(b.getType())){ tryBreakHead(p,b,dir,horiz,isFall,isGlideFly,probe.getX(),probe.getZ()); return; }
            }
        }
        // Suelo bajo pies para trapdoor/jarrón/cabeza al correr/saltar sobre ella (desde arriba)
        Block floorCheck=feet.getBlock().getRelative(BlockFace.DOWN);
        if(floorCheck.getType()!=Material.AIR){
            if(isTrapDoor(floorCheck.getType())){ tryBreakTrapdoor(p,floorCheck,dir,horiz,isFall,isGlideFly,feet.getX(),feet.getZ()); }
            else if(isVase(floorCheck.getType())){ tryBreakVase(p,floorCheck,dir,horiz,isFall,isGlideFly,feet.getX(),feet.getZ()); }
            else if(isHead(floorCheck.getType())){ tryBreakHead(p,floorCheck,dir,horiz,isFall,isGlideFly,feet.getX(),feet.getZ()); }
        }
        if(isFall){
            Block below=feet.getBlock().getRelative(BlockFace.DOWN);
            if(below.getType()!=Material.AIR){
                if(isDoor(below.getType())) tryBreakDoor(p,below,dir,horiz,true,isGlideFly,feet.getX(),feet.getZ());
                else if(isTrapDoor(below.getType())) tryBreakTrapdoor(p,below,dir,horiz,true,isGlideFly,feet.getX(),feet.getZ());
                else if(isVase(below.getType())) tryBreakVase(p,below,dir,horiz,true,isGlideFly,feet.getX(),feet.getZ());
                else if(isHead(below.getType())) tryBreakHead(p,below,dir,horiz,true,isGlideFly,feet.getX(),feet.getZ());
            }
            Block fb=feet.getBlock();
            if(isTrapDoor(fb.getType())) tryBreakTrapdoor(p,fb,dir,horiz,true,isGlideFly,feet.getX(),feet.getZ());
            else if(isVase(fb.getType())) tryBreakVase(p,fb,dir,horiz,true,isGlideFly,feet.getX(),feet.getZ());
            else if(isHead(fb.getType())) tryBreakHead(p,fb,dir,horiz,true,isGlideFly,feet.getX(),feet.getZ());
        }
    }

    private void tryBreakDoor(Player p, Block block, Vector dir, double horiz, boolean isFall, boolean isGlideFly, double ix, double iz){
        if(!(block.getState().getBlockData() instanceof Door door)) return;
        Block base=blockDown(block);
        boolean iron=isIron(base.getType()); boolean copper=isCopperDoor(base.getType());
        if(!isFall){
            int req=isGlideFly?SUSTAIN_TICKS_GLIDE:iron?SUSTAIN_TICKS_IRON:copper?SUSTAIN_TICKS_COPPER:SUSTAIN_TICKS_WOOD;
            Integer t=sustain.get(p.getUniqueId()); if(t==null||t<req) return;
        }
        // Puerta cerrada se puede embestir desde cualquier lado; abierta solo bordes
        // Puerta requiere sprint (no solo caminata) para no romper caminando
        if(!isFall && !isGlideFly && !p.isSprinting()) return;
        if(!isFall && !door.isOpen()){
            // Cerrada: no exige dir hacia puerta, permite ambos lados
        } else if(!isFall){
            Location c=base.getLocation().clone().add(0.5,0.5,0.5);
            Vector toDoor=new Vector(c.getX()-ix,0,c.getZ()-iz); if(toDoor.lengthSquared()<1e-4) return;
            toDoor.normalize(); if(dir.dot(toDoor)<0.20) return;
        }
        if(!doorIsHit(block,door,ix,iz,p.getY())) return;
        breakDoor(base, door, dir, horiz, isFall, p);
    }
    private void tryBreakTrapdoor(Player p, Block block, Vector dir, double horiz, boolean isFall, boolean isGlideFly, double ix, double iz){
        if(!(block.getState().getBlockData() instanceof TrapDoor trap)) return;
        boolean iron=isIronTrapdoor(block.getType()); boolean copper=isCopperTrapdoor(block.getType());
        if(!isFall){
            int req=isGlideFly?SUSTAIN_TICKS_GLIDE:iron?SUSTAIN_TICKS_IRON:copper?SUSTAIN_TICKS_COPPER:SUSTAIN_TICKS_WOOD;
            Integer t=sustain.get(p.getUniqueId()); if(t==null||t<req) return;
            // Desde arriba: permite aunque dir horizontal no apunte al centro (caída vertical)
            if(!isFall || Math.abs(p.getY()-(block.getY()+0.5))<0.6){
                Location c=block.getLocation().clone().add(0.5,0.5,0.5);
                Vector to=new Vector(c.getX()-ix,0,c.getZ()-iz); if(to.lengthSquared()>1e-4){to.normalize(); if(dir.dot(to)<0.10) return;}
            }
        }
        if(!trapdoorIsHit(block,trap,ix,iz,p.getY(),isFall)) return;
        breakTrapdoor(block,trap,dir,horiz,isFall);
    }
    private void tryBreakVase(Player p, Block block, Vector dir, double horiz, boolean isFall, boolean isGlideFly, double ix, double iz){
        Material m=block.getType(); if(!isVase(m)) return;
        // Jarrones/macetas se pueden derribar caminando (no solo sprint) con 1.5s
        boolean isWalking = horiz >= 0.08;
        if(!isFall && !isWalking && !(p.isSprinting()||p.isGliding()||p.isFlying())) return;
        boolean copper=m.name().contains("COPPER");
        if(!isFall){
            int req=isGlideFly?SUSTAIN_TICKS_GLIDE:copper?SUSTAIN_TICKS_COPPER:SUSTAIN_TICKS_VASE;
            // Para vase permite también caminata, usa mismo sustain
            Integer t=sustain.get(p.getUniqueId());
            // Si es caminata sin sprint, cuenta igual si horiz>0.08
            if(t==null||t<req){
                // Fallback: si lleva 1.5s caminando aunque no sprint, permite con menos
                if(!(isWalking && t!=null && t>=8)) return;
            }
            Location c=block.getLocation().clone().add(0.5,0.5,0.5);
            Vector to=new Vector(c.getX()-ix,0,c.getZ()-iz); if(to.lengthSquared()>1e-4){to.normalize(); if(dir.dot(to)<0.05) return;}
        }
        double bx=block.getX()+0.5,bz=block.getZ()+0.5;
        if(Math.abs(ix-bx)>0.75||Math.abs(iz-bz)>0.75) return;
        if(Math.abs(p.getY()-(block.getY()+0.5))>1.5) return;
        breakVase(block,dir,horiz,isFall);
    }

    private void tryBreakHead(Player p, Block block, Vector dir, double horiz, boolean isFall, boolean isGlideFly, double ix, double iz){
        Material m=block.getType(); if(!isHead(m)) return;
        if(!isFall && !isGlideFly){
            if(!p.isSprinting()) return;
            Integer t=sustain.get(p.getUniqueId()); if(t==null||t<SUSTAIN_TICKS_HEAD) return;
            Location c=block.getLocation().clone().add(0.5,0.5,0.5);
            Vector to=new Vector(c.getX()-ix,0,c.getZ()-iz); if(to.lengthSquared()>1e-4){to.normalize(); if(dir.dot(to)<0.20) return;}
        } else if(!isFall){
            Integer tt=sustain.get(p.getUniqueId()); if(tt==null||tt<SUSTAIN_TICKS_HEAD) return;
        }
        double bx=block.getX()+0.5,bz=block.getZ()+0.5;
        if(Math.abs(ix-bx)>0.85||Math.abs(iz-bz)>0.85) return;
        if(Math.abs(p.getY()-(block.getY()+0.5))>1.6) return;
        breakHead(block,dir,horiz,isFall);
    }
    private void breakHead(Block block, Vector dir, double horiz, boolean isFall){
        Material mat=block.getType(); BlockData data=block.getBlockData().clone();
        Location spawn=block.getLocation().clone().add(0.5,0.5,0.5); block.setType(Material.AIR);
        double weight=HEAD_WEIGHT; double factor=1.5/weight;
        double power=0.8+Math.min(1.2,horiz*1.8); factor*=power;
        double vH=(0.18+Math.min(0.55,horiz*1.4))*factor; double vU=(isFall?0.08:0.28)*factor;
        Vector vel=new Vector(dir.getX()*vH+(random.nextDouble()-0.5)*0.12, vU, dir.getZ()*vH+(random.nextDouble()-0.5)*0.12);
        float spin=(float)((1.5+Math.min(10,horiz*28))*0.9);
        spawnPhysicsHead(spawn,data,mat,vel,(float)((random.nextDouble()-0.5)*spin),(float)((random.nextDouble()-0.5)*spin*0.6f),(float)((random.nextDouble()-0.5)*spin));
        block.getWorld().playSound(spawn, Sound.BLOCK_BONE_BLOCK_BREAK, 1f, 0.8f);
    }
    private void spawnPhysicsHead(Location loc, BlockData data, Material mat, Vector vel,float yawR,float pitchR,float rollR){
        if(bodies.size()>=MAX_BODIES) return;
        BlockDisplay d=loc.getWorld().spawn(loc,BlockDisplay.class, e->{ e.setBlock(data); e.setBillboard(Display.Billboard.FIXED); e.setPersistent(false); e.setInterpolationDuration(1); e.setTeleportDuration(1); e.getPersistentDataContainer().set(key,PersistentDataType.BYTE,(byte)1); setTransformation(e,new Quaternionf(), new Vector3f(0.6f,0.6f,0.6f));});
        Interaction hit=loc.getWorld().spawn(loc,Interaction.class, i->{ i.setInteractionWidth(0.7f); i.setInteractionHeight(0.7f); i.setResponsive(true); i.getPersistentDataContainer().set(key,PersistentDataType.BYTE,(byte)3);});
        DoorBody b=new DoorBody(d.getUniqueId(), loc.clone(), Bukkit.getCurrentTick()); b.velocity=vel.clone(); b.yawRate=yawR; b.pitchRate=pitchR; b.rollRate=rollR; b.halfH=0.30; b.material=mat; b.isHead=true; b.interactionId=hit.getUniqueId(); bodies.add(b);
    }

    private boolean doorIsHit(Block block, Door door, double px,double pz,double py){
        Block base=blockDown(block); double cx=base.getX()+0.5, cz=base.getZ()+0.5; double fy=base.getY();
        if(py<fy-0.4||py>fy+2.2) return false;
        BlockFace f=door.getFacing(); Vector fv=faceVec(f), rv=rightVec(f);
        Vector pv=door.getHinge()==Door.Hinge.RIGHT?rv:rv.clone().multiply(-1);
        double dX=px-cx,dZ=pz-cz; double alongF=dX*fv.getX()+dZ*fv.getZ(); double alongP=dX*pv.getX()+dZ*pv.getZ();
        if(door.isOpen()){ double aP=Math.abs(alongP); return aP>=0.28&&aP<=0.55&&Math.abs(alongF)<=0.35; }
        return Math.abs(alongF)<=0.5&&Math.abs(alongP)<=0.5;
    }
    private boolean trapdoorIsHit(Block b,TrapDoor trap,double px,double pz,double py,boolean isFall){
        double bx=b.getX()+0.5,bz=b.getZ()+0.5,by=b.getY()+0.5;
        double dx=px-bx,dz=pz-bz,dy=py-by;
        if(Math.abs(dx)>0.85||Math.abs(dz)>0.85) return false;
        if(isFall) return Math.abs(dy)<=1.4; // desde arriba en caída
        if(trap.isOpen()) return Math.abs(dy)<=1.1;
        // cerrada en piso: permite desde arriba (py > by) con dy hasta 1.4
        if(py > by) return dy<=1.4 && dy>=-0.6;
        return Math.abs(dy)<=0.9;
    }
    private static Block blockDown(Block b){
        if(b.getState().getBlockData() instanceof Door d&&d.getHalf()==Door.Half.TOP) return b.getRelative(BlockFace.DOWN);
        return b;
    }
    private static boolean isDoor(Material m){return Tag.DOORS.isTagged(m);}
    private static boolean isTrapDoor(Material m){return Tag.TRAPDOORS.isTagged(m);}
    private static boolean isIronTrapdoor(Material m){return m==Material.IRON_TRAPDOOR;}
    private static boolean isCopperDoor(Material m){return m.name().contains("COPPER")&&Tag.DOORS.isTagged(m);}
    private static boolean isCopperTrapdoor(Material m){return m.name().contains("COPPER")&&Tag.TRAPDOORS.isTagged(m);}
    private static boolean isVase(Material m){ return m==Material.DECORATED_POT||m==Material.FLOWER_POT||m.name().startsWith("POTTED_");}
    private static boolean isHead(Material m){ return m==Material.PLAYER_HEAD||m==Material.PLAYER_WALL_HEAD||m==Material.SKELETON_SKULL||m==Material.WITHER_SKELETON_SKULL||m==Material.ZOMBIE_HEAD||m==Material.CREEPER_HEAD||m==Material.DRAGON_HEAD||m==Material.PIGLIN_HEAD; }

    private double getDoorWeight(Material m){
        if(m.name().contains("COPPER")) return 2.0;
        if(m==Material.IRON_DOOR) return 4.0;
        return 1.5;
    }
    private double getTrapdoorWeight(Material m){
        if(m.name().contains("COPPER")) return 1.5;
        if(m==Material.IRON_TRAPDOOR) return 3.0;
        return 0.5;
    }

    private void breakDoor(Block base, Door door, Vector dir, double horiz, boolean isFall, Player p){
        Block top=base.getRelative(BlockFace.UP);
        Material mat=base.getType();
        BlockData bottomData=base.getBlockData().clone();
        // Solo parte de abajo con longitud completa (2 bloques) para textura exacta y giro correcto
        Location spawn=base.getLocation().clone().add(0.5,1.0,0.5);
        base.setType(Material.AIR); top.setType(Material.AIR);
        double weight=getDoorWeight(mat);
        double factor=1.5/weight; // pesadez afecta impulso, no giro
        double power = 0.8 + Math.min(1.2, horiz*1.8);
        factor *= power;
        double vH=(0.20+Math.min(0.60,horiz*1.6))*factor;
        double vU=(isFall?0.08:0.35+Math.min(1.0,horiz*0.8))*factor;
        Vector vel=new Vector(dir.getX()*vH+(random.nextDouble()-0.5)*0.2, vU, dir.getZ()*vH+(random.nextDouble()-0.5)*0.2);
        float spin=(float)(2.0+Math.min(12.0,horiz*30)); // giro sin factor peso para que no se vea tiesa
        spawnPhysicsDoorSingle(spawn,bottomData,mat,vel,(float)((random.nextDouble()-0.5)*spin),(float)((random.nextDouble()-0.5)*spin*0.7f),(float)((random.nextDouble()-0.5)*spin));
        boolean iron=isIron(mat);
        Sound snd=iron?Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR:Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR;
        spawn.getWorld().playSound(spawn,snd,iron?1.5f:1.3f,iron?0.7f:0.8f);
    }
    private void breakTrapdoor(Block block, TrapDoor trap, Vector dir,double horiz,boolean isFall){
        Material mat=block.getType(); BlockData data=block.getBlockData().clone();
        Location spawn=block.getLocation().clone().add(0.5,0.5,0.5); block.setType(Material.AIR);
        double weight=getTrapdoorWeight(mat);
        double f=1.5/weight; // madera 3.0, cobre 1.0, hierro 0.5 (más pesada menos impulso)
        double power=0.8+Math.min(1.2,horiz*1.8); f*=power;
        double vH=(0.20+Math.min(0.60,horiz*1.6))*f; double vU=(isFall?0.05:0.35+Math.min(1.0,horiz*0.8))*f;
        Vector vel=new Vector(dir.getX()*vH+(random.nextDouble()-0.5)*0.15, vU+(isFall?0:0.15), dir.getZ()*vH+(random.nextDouble()-0.5)*0.15);
        float spin=(float)((2+Math.min(12,horiz*30))*f);
        spawnPhysicsTrapdoor(spawn,data,mat,vel,(float)((random.nextDouble()-0.5)*spin),(float)((random.nextDouble()-0.5)*spin*0.7f),(float)((random.nextDouble()-0.5)*spin));
        boolean iron=isIronTrapdoor(mat);
        Sound snd=iron?Sound.BLOCK_IRON_TRAPDOOR_CLOSE:Sound.BLOCK_WOODEN_TRAPDOOR_CLOSE;
        block.getWorld().playSound(spawn,snd,1.2f,0.8f); block.getWorld().playSound(spawn,Sound.BLOCK_WOOD_BREAK,1f,0.9f);
    }
    private void breakVase(Block block, Vector dir,double horiz,boolean isFall){
        Material mat=block.getType(); BlockData d=block.getBlockData().clone();
        Location spawn=block.getLocation().clone().add(0.5,0.5,0.5); block.setType(Material.AIR);
        double vH=0.22+Math.min(0.55,horiz*1.5); double vU=0.30+(isFall?Math.min(0.8,horiz*0.7):0.18);
        Vector vel=new Vector(dir.getX()*vH+(random.nextDouble()-0.5)*0.18, vU, dir.getZ()*vH+(random.nextDouble()-0.5)*0.18);
        float spin=(float)(3+Math.min(14,horiz*32));
        spawnPhysicsVase(spawn,d,mat,vel,(float)((random.nextDouble()-0.5)*spin),(float)((random.nextDouble()-0.5)*spin*0.8f),(float)((random.nextDouble()-0.5)*spin));
        block.getWorld().playSound(spawn,Sound.BLOCK_DECORATED_POT_BREAK,1.1f,0.9f); block.getWorld().playSound(spawn,Sound.BLOCK_GLASS_BREAK,0.9f,1.1f);
    }
    private static boolean isIron(Material m){return m==Material.IRON_DOOR;}

    private void spawnPhysicsDoor(Location loc, BlockData bottom, BlockData top, Material mat, Vector vel, float yawR,float pitchR,float rollR){
        if(bodies.size()>=MAX_BODIES) return;
        World w=loc.getWorld();
        // Puerta completa alineada: ambos displays en el centro b.pos, con translación Y diferenciada (-1 y 0) para que no se separen al rotar
        BlockDisplay bottomDisp=w.spawn(loc.clone(), BlockDisplay.class, d->{
            d.setBlock(bottom); d.setBillboard(Display.Billboard.FIXED); d.setPersistent(false);
            d.setInterpolationDuration(1); d.setTeleportDuration(1);
            d.getPersistentDataContainer().set(key,PersistentDataType.BYTE,(byte)1);
            d.setTransformation(new Transformation(new Vector3f(-0.5f,-1f,-0.4f), new Quaternionf(), new Vector3f(1f,1f,0.5f), new Quaternionf()));
        });
        BlockDisplay topDisp=w.spawn(loc.clone(), BlockDisplay.class, d->{
            d.setBlock(top); d.setBillboard(Display.Billboard.FIXED); d.setPersistent(false);
            d.setInterpolationDuration(1); d.setTeleportDuration(1);
            d.getPersistentDataContainer().set(key,PersistentDataType.BYTE,(byte)2);
            d.setTransformation(new Transformation(new Vector3f(-0.5f,0f,-0.4f), new Quaternionf(), new Vector3f(1f,1f,0.5f), new Quaternionf()));
        });
        Interaction hitbox=w.spawn(loc.clone(), Interaction.class, i->{
            i.setInteractionWidth(1.6f); i.setInteractionHeight(2.4f);
            i.setResponsive(true); i.getPersistentDataContainer().set(key,PersistentDataType.BYTE,(byte)3);
        });
        DoorBody body=new DoorBody(bottomDisp.getUniqueId(), loc.clone(), Bukkit.getCurrentTick());
        body.velocity=vel.clone(); body.yawRate=yawR; body.pitchRate=pitchR; body.rollRate=rollR;
        body.halfH=1.0; body.material=mat; body.isTrapdoor=false; body.topId=topDisp.getUniqueId(); body.interactionId=hitbox.getUniqueId();
        bodies.add(body);
    }
    private void spawnPhysicsDoorSingle(Location loc, BlockData data, Material mat, Vector vel,float yawR,float pitchR,float rollR){
        if(bodies.size()>=MAX_BODIES) return;
        World w=loc.getWorld();
        // Grosor aumentado a 0.35 para no verse como palo y longitud completa 2 bloques
        BlockDisplay disp=w.spawn(loc.clone(), BlockDisplay.class, d->{
            d.setBlock(data); d.setBillboard(Display.Billboard.FIXED); d.setPersistent(false);
            d.setInterpolationDuration(1); d.setTeleportDuration(1);
            d.getPersistentDataContainer().set(key,PersistentDataType.BYTE,(byte)1);
            d.setTransformation(new Transformation(new Vector3f(-0.5f,-1f,-0.4f), new Quaternionf(), new Vector3f(1f,2f,0.8f), new Quaternionf()));
        });
        Interaction hitbox=w.spawn(loc.clone(), Interaction.class, i->{
            i.setInteractionWidth(1.6f); i.setInteractionHeight(2.4f);
            i.setResponsive(true); i.getPersistentDataContainer().set(key,PersistentDataType.BYTE,(byte)3);
        });
        DoorBody body=new DoorBody(disp.getUniqueId(), loc.clone(), Bukkit.getCurrentTick());
        body.velocity=vel.clone(); body.yawRate=yawR; body.pitchRate=pitchR; body.rollRate=rollR; body.halfH=1.0; body.material=mat; body.isTrapdoor=false; body.topId=null; body.interactionId=hitbox.getUniqueId(); bodies.add(body);
    }

    private void spawnPhysicsTrapdoor(Location loc, BlockData data, Material mat, Vector vel,float yawR,float pitchR,float rollR){
        if(bodies.size()>=MAX_BODIES) return;
        BlockDisplay d=loc.getWorld().spawn(loc,BlockDisplay.class, e->{ e.setBlock(data); e.setBillboard(Display.Billboard.FIXED); e.setPersistent(false); e.setInterpolationDuration(1); e.setTeleportDuration(1); e.getPersistentDataContainer().set(key,PersistentDataType.BYTE,(byte)1); setTransformation(e,new Quaternionf(), new Vector3f(1f,0.1875f,1f));});
        Interaction hit=loc.getWorld().spawn(loc,Interaction.class, i->{ i.setInteractionWidth(1.4f); i.setInteractionHeight(0.7f); i.setResponsive(true); i.getPersistentDataContainer().set(key,PersistentDataType.BYTE,(byte)3);});
        DoorBody b=new DoorBody(d.getUniqueId(), loc.clone(), Bukkit.getCurrentTick()); b.velocity=vel.clone(); b.yawRate=yawR; b.pitchRate=pitchR; b.rollRate=rollR; b.halfH=0.15; b.material=mat; b.isTrapdoor=true; b.interactionId=hit.getUniqueId(); bodies.add(b);
    }
    private void spawnPhysicsVase(Location loc, BlockData data, Material mat, Vector vel,float yawR,float pitchR,float rollR){
        if(bodies.size()>=MAX_BODIES) return;
        BlockDisplay d=loc.getWorld().spawn(loc,BlockDisplay.class, e->{ e.setBlock(data); e.setBillboard(Display.Billboard.FIXED); e.setPersistent(false); e.setInterpolationDuration(1); e.setTeleportDuration(1); e.getPersistentDataContainer().set(key,PersistentDataType.BYTE,(byte)1); setTransformation(e,new Quaternionf(), new Vector3f(0.65f,1f,0.65f));});
        Interaction hit=loc.getWorld().spawn(loc,Interaction.class, i->{ i.setInteractionWidth(1.1f); i.setInteractionHeight(1.4f); i.setResponsive(true); i.getPersistentDataContainer().set(key,PersistentDataType.BYTE,(byte)3);});
        DoorBody b=new DoorBody(d.getUniqueId(), loc.clone(), Bukkit.getCurrentTick()); b.velocity=vel.clone(); b.yawRate=yawR; b.pitchRate=pitchR; b.rollRate=rollR; b.halfH=0.5; b.material=mat; b.isVase=true; b.interactionId=hit.getUniqueId(); bodies.add(b);
    }

    private void stepPhysics(){
        Iterator<DoorBody> it=bodies.iterator(); long now=Bukkit.getCurrentTick();
        while(it.hasNext()){
            DoorBody b=it.next();
            // Culling: si no hay jugador a 64 bloques, pausa física (ahorra TPS) pero mantiene display
            World w=b.pos.getWorld();
            if(w!=null && w.getNearbyPlayers(b.pos,64).isEmpty()){
                // Solo avanza TTL, sin step
                if(now - b.createdTick > BODY_TTL_TICKS){
                    if(w!=null){
                        if(b.isVase){ for(ItemStack drop: getVaseDrops(b.material)) w.dropItemNaturally(b.pos, drop); }
                        else { BlockDisplay disp=(BlockDisplay) Bukkit.getEntity(b.entityId); BlockData data=disp!=null?disp.getBlock():Bukkit.createBlockData(b.material); w.dropItemNaturally(b.pos, exactDrop(b.material, data));}
                    }
                    removeBodyAndEntities(b); it.remove();
                }
                continue;
            }
            if(now - b.createdTick > BODY_TTL_TICKS){
                // despawn dropeando item exacto si no fue recogido
                if(w!=null){
                    if(b.isVase){
                        for(ItemStack drop: getVaseDrops(b.material)) w.dropItemNaturally(b.pos, drop);
                    } else {
                        BlockDisplay disp=(BlockDisplay) Bukkit.getEntity(b.entityId);
                        BlockData data=disp!=null?disp.getBlock():Bukkit.createBlockData(b.material);
                        w.dropItemNaturally(b.pos, exactDrop(b.material, data));
                    }
                }
                removeBodyAndEntities(b); it.remove(); continue;
            }
            Entity ent=Bukkit.getEntity(b.entityId);
            if(!(ent instanceof BlockDisplay disp)||!ent.isValid()||ent.isDead()){ removeBodyAndEntities(b); it.remove(); continue; }
            // ─── Dormido: sin física ni paquetes de sync, solo chequeo barato de suelo ───
            if(b.asleep){
                if((now + b.entityId.hashCode()) % 10 == 0){
                    Vector3f half=CollisionEngine.halfExtentsFor(b.isHead,b.isVase,b.isTrapdoor);
                    int gy=(int)Math.floor(b.pos.getY()-half.y-1e-4);
                    if(!isSolid(w,(int)Math.floor(b.pos.getX()),gy-1,(int)Math.floor(b.pos.getZ()))){
                        b.asleep=false; b.sleepTicks=0; b.landed=false;
                    }
                }
                continue;
            }
            if(!b.landed || b.sleepTicks < 14) step(b);
            // Mantener escala real siempre (no minimizar en reposo) con colisiones 3D exactas
            if(b.isHead) setTransformation(disp,toQuat(b.pitch,b.yaw,b.roll), new Vector3f(0.6f,0.6f,0.6f));
            else if(b.isVase) setTransformation(disp,toQuat(b.pitch,b.yaw,b.roll), new Vector3f(0.65f,1f,0.65f));
            else if(b.isTrapdoor) setTransformation(disp,toQuat(b.pitch,b.yaw,b.roll), new Vector3f(1f,0.1875f,1f));
            else {
                // puerta single con grosor 0.35 y longitud 2 (no palo delgado)
                Quaternionf q=toQuat(b.pitch,b.yaw,b.roll);
                disp.setTransformation(new Transformation(new Vector3f(-0.5f,-1f,-0.4f), q, new Vector3f(1f,2f,0.8f), new Quaternionf()));
                // topId ya no se usa (single), pero si existe legacy, sincroniza igual
                Entity topEnt=b.topId!=null?Bukkit.getEntity(b.topId):null;
                if(topEnt instanceof BlockDisplay topDisp){
                    topDisp.setTransformation(new Transformation(new Vector3f(-0.5f,0f,-0.4f), q, new Vector3f(1f,1f,0.5f), new Quaternionf()));
                    topDisp.teleport(b.pos.clone());
                }
            }
            disp.teleport(b.pos);
            // Sync hitbox (centrado en b.pos, sin offset - Interaction ya es centrado)
            if(b.interactionId!=null){
                Entity inter=Bukkit.getEntity(b.interactionId);
                if(inter instanceof Interaction in) in.teleport(b.pos.clone());
            }
            if(b.landed && b.isVase) {
                // jarron landed sigue visible igual, no aplanar
            }
        }
        // ─── Cuerpo-cuerpo: SAT + separación + impulso (pila de objetos) ───
        resolveBodyBody(now);
    }

    private void resolveBodyBody(long now){
        List<DoorBody> near = bodies;
        for(int i=0;i<near.size();i++){
            DoorBody a=near.get(i);
            for(int j=i+1;j<near.size();j++){
                DoorBody b=near.get(j);
                if(!a.pos.getWorld().equals(b.pos.getWorld())) continue;
                double dist = a.pos.distanceSquared(b.pos);
                double maxR = 2.6;
                if(dist > maxR*maxR) continue;
                Quaternionf qa=toQuat(a.pitch,a.yaw,a.roll), qb=toQuat(b.pitch,b.yaw,b.roll);
                Vector3f ha=CollisionEngine.halfExtentsFor(a.isHead,a.isVase,a.isTrapdoor);
                Vector3f hb=CollisionEngine.halfExtentsFor(b.isHead,b.isVase,b.isTrapdoor);
                CollisionEngine.BodyHit c=CollisionEngine.checkBodyBody(
                    new Vector3f((float)a.pos.getX(),(float)a.pos.getY(),(float)a.pos.getZ()), qa, ha,
                    new Vector3f((float)b.pos.getX(),(float)b.pos.getY(),(float)b.pos.getZ()), qb, hb);
                if(!c.hit) continue;
                double wa=weightOf(a), wb=weightOf(b);
                double invA=1.0/wa, invB=1.0/wb, invSum=invA+invB;
                double pen=c.penetration*0.55;
                // Separación posicional proporcional a la masa inversa
                Vector n=c.normal;
                a.pos.add(n.clone().multiply(-pen*invA/invSum));
                b.pos.add(n.clone().multiply(pen*invB/invSum));
                // Despertar si estaba dormido
                if(a.asleep) a.asleep=false;
                if(b.asleep) b.asleep=false;
                // Impulso de restitución a lo largo de la normal
                Vector va=a.velocity.clone(), vb=b.velocity.clone();
                double closing=(va.dot(n)-vb.dot(n));
                if(closing<0){
                    double e=(CollisionEngine.getRestitution(a.material)+CollisionEngine.getRestitution(b.material))*0.5;
                    double impulse=-(1+e)*closing/invSum;
                    a.velocity.add(n.clone().multiply(impulse*invA));
                    b.velocity.subtract(n.clone().multiply(impulse*invB));
                    float r=(float)(0.5+Math.random()*2);
                    a.yawRate+=(float)((Math.random()-0.5)*r); b.yawRate+=(float)((Math.random()-0.5)*r);
                    a.pitchRate+=(float)((Math.random()-0.5)*r); b.pitchRate+=(float)((Math.random()-0.5)*r);
                    a.landed=false; b.landed=false;
                    if(a.isVase && a.bounces>4) shatterVase(a);
                    if(b.isVase && b.bounces>4) shatterVase(b);
                }
            }
        }
    }
    private double weightOf(DoorBody b){
        if(b.isTrapdoor) return getTrapdoorWeight(b.material);
        if(b.isVase) return 0.9;
        if(b.isHead) return HEAD_WEIGHT;
        return getDoorWeight(b.material);
    }

    private void step(DoorBody b){
        World w=b.pos.getWorld(); if(w==null||b.pos.getY()<w.getMinHeight()){b.landed=true; return;}
        boolean wasLanded = b.landed;
        double speedSq0 = b.velocity.lengthSquared();
        double ang0 = Math.abs(b.yawRate)+Math.abs(b.pitchRate)+Math.abs(b.rollRate);
        // Si está aterrizado y casi quieto, no aplicar física (evita micro-rebote que reportas)
        if (wasLanded && speedSq0 < 0.008 && ang0 < 0.7 && b.bounces >= 1) {
            b.velocity.setY(0);
            b.velocity.setX(b.velocity.getX()*0.88); b.velocity.setZ(b.velocity.getZ()*0.88);
            if (b.velocity.lengthSquared() < 0.001) { b.velocity.setX(0); b.velocity.setZ(0); }
            b.yawRate*=0.88f; b.pitchRate*=0.88f; b.rollRate*=0.88f;
            if (Math.abs(b.yawRate)<0.10) b.yawRate=0;
            if (Math.abs(b.pitchRate)<0.10) b.pitchRate=0;
            if (Math.abs(b.rollRate)<0.10) b.rollRate=0;
            b.sleepTicks++;
            if (b.sleepTicks > 4) { b.asleep = true; return; }
            // Aún no dormir, pero no aplicar gravedad este tick
            b.yaw+=b.yawRate; b.pitch+=b.pitchRate; b.roll+=b.rollRate;
            return;
        }
        if (wasLanded && speedSq0 < 0.015) {
            // Suaviza gravedad en suelo
            b.velocity.setY(b.velocity.getY()*0.92);
            if (Math.abs(b.velocity.getY()) < 0.02) b.velocity.setY(0);
        }
        // ─── Física base ───
        if (!wasLanded || speedSq0 > 0.002) b.velocity.setY(b.velocity.getY()-GRAVITY);
        b.velocity.setX(b.velocity.getX()*AIR_DRAG); b.velocity.setY(b.velocity.getY()*AIR_DRAG); b.velocity.setZ(b.velocity.getZ()*AIR_DRAG);
        float angDrag = wasLanded ? 0.88f : 0.985f;
        b.yawRate*=angDrag; b.pitchRate*=angDrag; b.rollRate*=angDrag;
        if (wasLanded) { b.yawRate*=0.94f; b.pitchRate*=0.94f; b.rollRate*=0.94f; }
        b.yaw+=b.yawRate; b.pitch+=b.pitchRate; b.roll+=b.rollRate;
        if (wasLanded && b.velocity.lengthSquared()<0.002) {
            if (Math.abs(b.yawRate)<0.08) b.yawRate=0;
            if (Math.abs(b.pitchRate)<0.08) b.pitchRate=0;
            if (Math.abs(b.rollRate)<0.08) b.rollRate=0;
        }
        Quaternionf q=toQuat(b.pitch,b.yaw,b.roll);
        Vector3f half = CollisionEngine.halfExtentsFor(b.isHead,b.isVase,b.isTrapdoor);
        Vector vel=b.velocity.clone();
        Vector nextPos=b.pos.toVector().clone().add(vel);
        // ─── CCD continuo: swept SAT OBB contra VoxelShape real ───
        CollisionEngine.Hit hit=CollisionEngine.checkOBBSwept(w, b.pos.toVector(), nextPos, q, half);
        if(hit.hit){
            double wgt=b.isTrapdoor?getTrapdoorWeight(b.material):b.isVase?0.9:b.isHead?HEAD_WEIGHT:getDoorWeight(b.material);
            Vector n=hit.normal;
            double dot=vel.dot(n);
            boolean realImpact = dot < -0.08;
            if(realImpact){
                double matRest = hit.primaryBlock!=null ? CollisionEngine.getRestitution(hit.primaryBlock.getType()) : RESTITUTION;
                // Amortigua restitución tras muchos rebotes para no brincar infinito
                if (b.bounces >= 3) matRest *= 0.45;
                if (b.bounces >= MAX_BOUNCES) matRest *= 0.3;
                Vector reflected=CollisionEngine.resolveVelocity(vel, hit, RESTITUTION, GROUND_FRICTION, wgt, b.yawRate, b.pitchRate, matRest);
                vel=reflected;
                // Limita spin cuando rebota en suelo (evita trompo)
                float spinScale = (hit.normal.getY()>0.5) ? 0.35f : 1.0f;
                b.yawRate+=CollisionEngine.computeImpactSpin(vel, n, half, wgt)*spinScale;
                b.pitchRate+=CollisionEngine.computeImpactSpin(vel, n, half, wgt)*0.5f*spinScale;
                b.rollRate+=(float)((Math.random()-0.5)*2.5*spinScale);
                b.bounces++;
                if(b.isVase && b.bounces>4){ shatterVase(b); return; }
                if(Math.abs(n.getX())>0.5 || Math.abs(n.getZ())>0.5){
                    vel=CollisionEngine.slideAlongWall(vel, n, getFrictionSafe(hit.primaryBlock));
                }
                // Si rebote es muy pequeño y es suelo, considera aterrizado
                if (n.getY()>0.5 && vel.lengthSquared()<0.015 && b.bounces>=2) {
                    vel.setY(Math.min(vel.getY(), 0.04));
                    vel.setX(vel.getX()*0.82); vel.setZ(vel.getZ()*0.82);
                }
            } else {
                double vn=vel.dot(n);
                if(vn<0) vel.subtract(n.clone().multiply(vn));
                b.bounces=Math.min(b.bounces,MAX_BOUNCES-1);
                // Fricción de reposo
                if (n.getY()>0.5) { vel.setX(vel.getX()*0.92); vel.setZ(vel.getZ()*0.92); }
            }
            Vector contact=b.pos.toVector().clone().add(b.velocity.clone().multiply(hit.t));
            contact = b.pos.toVector().clone().add(vel.clone().multiply(hit.t));
            nextPos=contact.clone().add(n.clone().multiply(hit.penetration+0.015));
            if(n.getY()>0.5 && vel.getY()<0.22 && vel.lengthSquared()<0.012){
                b.landed=true;
                // Usa half efectivo en mundo (rotación puede hacer que altura sea 0.15 si está tumbada)
                org.bukkit.util.BoundingBox abh = CollisionEngine.approxAABB(nextPos, q, half);
                double worldHalfYh = (abh.getMaxY()-abh.getMinY())*0.5;
                double surface=Math.floor(nextPos.getY()-worldHalfYh+0.02)+1+worldHalfYh;
                nextPos.setY(surface); vel.setX(vel.getX()*0.55); vel.setZ(vel.getZ()*0.55); vel.setY(0);
                if (vel.lengthSquared()<0.002) { vel.setX(0); vel.setZ(0); }
                b.yawRate*=0.5f; b.pitchRate*=0.5f; b.rollRate*=0.5f;
            }
        }
        // ─── Fallback suelo (sin OBB hit) — usa AABB real para no atravesar/botar ───
        org.bukkit.util.BoundingBox ab = CollisionEngine.approxAABB(nextPos, q, half);
        double bottom=ab.getMinY(); int by=(int)Math.floor(bottom-1e-4);
        double worldHalfY = (ab.getMaxY()-ab.getMinY())*0.5;
        boolean solidBelow = by<w.getMinHeight()||isSolid(w,(int)Math.floor(nextPos.getX()),by,(int)Math.floor(nextPos.getZ()));
        if(solidBelow){
            double surface=Math.max(w.getMinHeight(),by+1.0)+worldHalfY;
            // Clamp para no atravesar piso aunque hit falló
            if (nextPos.getY() < surface) {
                if(vel.getY()<-0.06 && b.bounces<MAX_BOUNCES && !b.landed){
                    double rest = RESTITUTION * (b.bounces>=3?0.4:1.0);
                    vel.setY(-vel.getY()*rest); vel.setX(vel.getX()*GROUND_FRICTION); vel.setZ(vel.getZ()*GROUND_FRICTION);
                    b.bounces++; nextPos.setY(surface+0.01);
                    b.yawRate*=0.7f; b.pitchRate*=0.7f;
                } else {
                    if(!b.landed){
                        if(vel.lengthSquared()<0.008){ b.landed=true; nextPos.setY(surface); vel.setX(0); vel.setY(0); vel.setZ(0); b.yawRate*=0.3f; b.pitchRate*=0.3f; b.rollRate*=0.3f; }
                        else { vel.setY(Math.max(0,vel.getY())); nextPos.setY(surface+0.01); vel.setX(vel.getX()*0.85); vel.setZ(vel.getZ()*0.85); }
                    } else { nextPos.setY(surface); vel.setY(0); if(vel.lengthSquared()<0.003){vel.setX(0); vel.setZ(0);} }
                }
            } else if (!hit.hit && b.landed && nextPos.getY() > surface+0.12) {
                // Si está aterrizado pero flotando mucho, deja caer
                b.landed = false;
            }
        } else {
            // En aire, no está landed
            if (b.landed && vel.getY() < -0.08) b.landed = false;
        }
        // Anti-tunneling final: nunca bajo piso (usa half efectivo rotado)
        {
            org.bukkit.util.BoundingBox ab2 = CollisionEngine.approxAABB(nextPos, q, half);
            double wHalf2 = (ab2.getMaxY()-ab2.getMinY())*0.5;
            double surfaceMin = Math.max(w.getMinHeight(), (int)Math.floor(nextPos.getY()-wHalf2-0.02)+1)+wHalf2;
            int checkY = (int)Math.floor(ab2.getMinY()-1e-4);
            if (isSolid(w,(int)Math.floor(nextPos.getX()),checkY,(int)Math.floor(nextPos.getZ())) && ab2.getMinY() < checkY+1) {
                nextPos.setY(surfaceMin);
                if (vel.getY()<0) vel.setY(0);
            }
        }
        b.velocity.setX(vel.getX()); b.velocity.setY(vel.getY()); b.velocity.setZ(vel.getZ());
        b.pos.setX(nextPos.getX()); b.pos.setY(nextPos.getY()); b.pos.setZ(nextPos.getZ());

        // ─── Sueño: body en reposo salta física pesada (ahorra TPS) ───
        double angKinetic = Math.abs(b.yawRate)+Math.abs(b.pitchRate)+Math.abs(b.rollRate);
        double speedSq = vel.lengthSquared();
        if(b.landed && speedSq<0.0045 && angKinetic<3.2 && b.bounces>=1){
            b.sleepTicks++;
            if(b.sleepTicks>10) { b.asleep=true; b.yawRate*=0.6f; b.pitchRate*=0.6f; b.rollRate*=0.6f; }
        } else if (!b.landed && speedSq<0.001 && angKinetic<1.0) {
            // También duerme si está casi quieto en aire (evita brinco micro)
            b.sleepTicks++;
            if(b.sleepTicks>18) b.asleep=true;
        } else {
            b.sleepTicks=0;
            b.asleep=false;
        }
    }

    private double getFrictionSafe(Block block){
        if(block==null) return 0.6;
        try{ return CollisionEngine.getFriction(block.getType()); }catch(Exception e){ return 0.6; }
    }

    private void pushNearbyBodies(Player p, Vector dir, double horiz){
        if(dir.lengthSquared()<1e-4) return;
        Vector nDir=dir.clone().normalize();
        for(DoorBody b: new java.util.ArrayList<>(bodies)){
            if(b.pos.getWorld()!=p.getWorld()) continue;
            double dist=b.pos.distance(p.getLocation().add(0,1,0));
            if(dist>1.8) continue;
            double radius=b.isHead?0.7:b.isVase?0.65:b.isTrapdoor?0.7:1.0;
            if(dist>radius) continue;
            Vector toBody=b.pos.toVector().subtract(p.getLocation().toVector()); toBody.setY(0);
            if(toBody.lengthSquared()<1e-4) continue;
            toBody.normalize();
            if(nDir.dot(toBody)<0.25) continue;
            double wgt=b.isTrapdoor?getTrapdoorWeight(b.material):b.isVase?0.9:b.isHead?HEAD_WEIGHT:getDoorWeight(b.material);
            double push=0.18+Math.min(0.35,horiz*0.6);
            push*=1.6/wgt;
            b.velocity.add(nDir.clone().multiply(push));
            b.velocity.setY(b.velocity.getY()+0.08);
            b.yawRate+=(float)((Math.random()-0.5)*6);
            b.pitchRate+=(float)((Math.random()-0.5)*6);
            if(b.asleep){ b.asleep=false; b.sleepTicks=0; }
            b.landed=false;
        }
    }

    private static boolean isSolid(World w,int x,int y,int z){
        if(y<w.getMinHeight()||y>=w.getMaxHeight()) return false;
        Block b=w.getBlockAt(x,y,z);
        if(b.getType().isAir()) return false;
        // Detecta piso/pared/techo de forma generosa: no pasable o sólido (incluye slabs, escaleras)
        return !b.isPassable() || b.getType().isSolid();
    }
    private static float RESTITION_DAMPEN(){return 0.35f;}
    private void shatterVase(DoorBody b){
        World w=b.pos.getWorld();
        if(w!=null){
            BlockDisplay disp=(BlockDisplay) Bukkit.getEntity(b.entityId);
            BlockData data=disp!=null?disp.getBlock():Bukkit.createBlockData(b.material);
            if(b.material==Material.DECORATED_POT){
                w.dropItemNaturally(b.pos, exactDrop(b.material, data));
            } else {
                for(ItemStack drop: getVaseDrops(b.material)) w.dropItemNaturally(b.pos, drop);
            }
            w.playSound(b.pos, Sound.BLOCK_DECORATED_POT_BREAK,1f,0.9f);
            w.playSound(b.pos, Sound.BLOCK_GLASS_BREAK,1f,1f);
            w.spawnParticle(org.bukkit.Particle.BLOCK, b.pos.clone().add(0,0.5,0),18,0.3,0.3,0.3, Bukkit.createBlockData(b.material));
        }
        removeBodyAndEntities(b);
        // No bodies.remove aquí para evitar ConcurrentModificationException; el llamante hará it.remove()
    }
    private static java.util.List<ItemStack> getVaseDrops(Material m){
        java.util.List<ItemStack> out=new java.util.ArrayList<>();
        if(m==Material.DECORATED_POT){ out.add(new ItemStack(Material.DECORATED_POT,1)); }
        else if(m==Material.FLOWER_POT) out.add(new ItemStack(Material.FLOWER_POT,1));
        else if(m.name().startsWith("POTTED_")){ out.add(new ItemStack(Material.FLOWER_POT,1)); try{ out.add(new ItemStack(Material.valueOf(m.name().substring(7)),1)); }catch(Exception ignored){} }
        else out.add(new ItemStack(m,1));
        return out;
    }
    private ItemStack exactDrop(Material m, BlockData data){
        // Drop exacto al bloque original: para jarrones decorados intenta preservar, fallback a item simple
        return new ItemStack(m,1);
    }

    private void onInteract(Player player, Entity clicked){
        if(player.getGameMode()==GameMode.SPECTATOR) return;
        DoorBody target=null;
        if(clicked instanceof BlockDisplay d && d.getPersistentDataContainer().has(key,PersistentDataType.BYTE)) target=bodies.stream().filter(b->b.entityId.equals(d.getUniqueId())|| (b.topId!=null&&b.topId.equals(d.getUniqueId()))).findFirst().orElse(null);
        else if(clicked instanceof Interaction inter && inter.getPersistentDataContainer().has(key,PersistentDataType.BYTE)) target=bodies.stream().filter(b->b.interactionId!=null&&b.interactionId.equals(inter.getUniqueId())).findFirst().orElse(null);
        else return;
        if(target==null) return;
        // Solo tras 5s se puede recoger
        if(Bukkit.getCurrentTick() - target.createdTick < PICKUP_DELAY_TICKS){
            long left=(PICKUP_DELAY_TICKS - (Bukkit.getCurrentTick()-target.createdTick))/20;
            player.sendActionBar("§eEspera "+left+"s para recoger");
            return;
        }
        Material mat=target.material;
        // Drop exacto al bloque original (preserva textura, tipo cobre, etc)
        Entity dispEnt=Bukkit.getEntity(target.entityId);
        BlockData data=dispEnt instanceof BlockDisplay bd?bd.getBlock():Bukkit.createBlockData(mat);
        ItemStack drop=exactDrop(mat, data);
        // Limpieza entidades
        removeBodyAndEntities(target); bodies.remove(target);
        Entity disp=Bukkit.getEntity(target.entityId); if(disp!=null) disp.remove();
        Entity top=target.topId!=null?Bukkit.getEntity(target.topId):null; if(top!=null) top.remove();
        Entity inter=target.interactionId!=null?Bukkit.getEntity(target.interactionId):null; if(inter!=null) inter.remove();
        HashMap<Integer,ItemStack> left=player.getInventory().addItem(drop);
        if(!left.isEmpty()) left.values().forEach(l->player.getWorld().dropItemNaturally(player.getLocation(),l));
        player.playSound(player.getLocation(),Sound.ENTITY_ITEM_PICKUP,0.8f,1.4f);
    }
    private void removeBody(UUID id){ DoorBody b=bodies.stream().filter(x->x.entityId.equals(id)|| (x.topId!=null&&x.topId.equals(id))|| (x.interactionId!=null&&x.interactionId.equals(id))).findFirst().orElse(null); if(b!=null){ removeBodyAndEntities(b); bodies.remove(b);} else removeEntity(id); }
    private void removeBodyAndEntities(DoorBody b){
        if(b==null) return;
        Entity e1=Bukkit.getEntity(b.entityId); if(e1!=null) e1.remove();
        if(b.topId!=null){ Entity e2=Bukkit.getEntity(b.topId); if(e2!=null) e2.remove(); }
        if(b.interactionId!=null){ Entity e3=Bukkit.getEntity(b.interactionId); if(e3!=null) e3.remove(); }
    }
    private void removeEntity(UUID id){ Entity g=Bukkit.getEntity(id); if(g!=null) g.remove(); }

    private static Quaternionf flat(){return new Quaternionf();}
    private static Quaternionf toQuat(float p,float y,float r){return new Quaternionf().rotationXYZ((float)Math.toRadians(p),(float)Math.toRadians(y),(float)Math.toRadians(r));}
    private static void setTransformation(BlockDisplay d, Quaternionf q, Vector3f s){ d.setTransformation(new Transformation(translationForScale(s),q,s,new Quaternionf()));}
    private static Vector3f translationForScale(Vector3f s){
        // Centra el bloque en la entidad para relleno exacto y textura completa
        if(s.x==1f && s.y==1f && s.z==0.1875f) return new Vector3f(-0.5f,0f,-0.09375f); // puerta
        if(s.x==1f && s.y==0.1875f && s.z==1f) return new Vector3f(-0.5f,0f,-0.5f); // trapdoor
        if(s.x==0.65f) return new Vector3f(-0.325f,0f,-0.325f); // jarrón
        if(s.x==0.6f) return new Vector3f(-0.3f,0f,-0.3f); // cabeza 0.6
        return new Vector3f(-s.x/2,0,-s.z/2);
    }
    private static Vector faceVec(BlockFace f){ return switch(f){ case NORTH->new Vector(0,0,-1); case SOUTH->new Vector(0,0,1); case EAST->new Vector(1,0,0); case WEST->new Vector(-1,0,0); default->new Vector(0,0,-1); };}
    private static Vector rightVec(BlockFace f){ return switch(f){ case NORTH->new Vector(1,0,0); case SOUTH->new Vector(-1,0,0); case EAST->new Vector(0,0,1); case WEST->new Vector(0,0,-1); default->new Vector(1,0,0); };}
    public NamespacedKey key(){return key;}

    private static final class DoorBody{
        final UUID entityId; UUID topId; UUID interactionId; final Location pos; final long createdTick;
        Vector velocity=new Vector(0,0,0); float yaw,pitch,roll,yawRate,pitchRate,rollRate; int bounces; boolean landed; double halfH=1.0; Material material; boolean isTrapdoor; boolean isVase; boolean isHead;
        boolean asleep; int sleepTicks;
        DoorBody(UUID id,Location pos,long t){this.entityId=id; this.pos=pos; this.createdTick=t;}
    }

    private final class PListener implements Listener{
        @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void onMove(PlayerMoveEvent e){PhysicsManager.this.onMove(e);}
        @EventHandler(ignoreCancelled=false) public void onBlockClick(PlayerInteractEvent e){
            if(e.getAction()!=Action.LEFT_CLICK_BLOCK) return;
            Block b=e.getClickedBlock(); if(b==null||!(isVase(b.getType())||isHead(b.getType()))) return;
            Player p=e.getPlayer();
            Vector dir=p.getEyeLocation().getDirection().setY(0); if(dir.lengthSquared()<1e-4) dir=new Vector(1,0,0); dir.normalize();
            double horiz=Math.max(0.25, p.getVelocity().length());
            if(p.getGameMode()==GameMode.CREATIVE && !p.isSneaking()) return;
            if(isHead(b.getType())) breakHead(b, dir, horiz, false);
            else breakVase(b, dir, horiz, false);
            e.setCancelled(true);
        }
        @EventHandler(ignoreCancelled=true) public void onPickupAt(PlayerInteractAtEntityEvent e){onInteract(e.getPlayer(), e.getRightClicked());}
        @EventHandler(ignoreCancelled=true) public void onPickup(PlayerInteractEntityEvent e){onInteract(e.getPlayer(), e.getRightClicked());}
        @EventHandler(ignoreCancelled=false) public void onHit(EntityDamageByEntityEvent e){
            if(!(e.getEntity() instanceof BlockDisplay) && !(e.getEntity() instanceof Interaction)) return;
            if(!(e.getEntity().getPersistentDataContainer().has(key,PersistentDataType.BYTE))) return;
            if(e.getDamager() instanceof Player p) onInteract(p,e.getEntity());
            e.setCancelled(true);
        }
    }
}
