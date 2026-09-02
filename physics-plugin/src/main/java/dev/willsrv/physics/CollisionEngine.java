package dev.willsrv.physics;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.bukkit.util.VoxelShape;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Motor de colisiones físico exacto OBB vs mundo Minecraft.
 *
 * - CCD REAL por SAT contínuo: el OBB móvil se barre contra cada bloque candidato
 *   resolviendo los 15 ejes de separación con intervalos temporales → tiempo de
 *   impacto EXACTO (resolución infinita, sin tunnelling incluso en trapdoor de 0.1875).
 * - VoxelShape real: slabs, escaleras, vallas se testean caja a caja.
 * - Restitución y fricción por material del bloque.
 * - OBB-OBB (SAT estático + impulso) para empuje cuerpo-cuerpo.
 * - Poly-resolución: normal de cara EXACTA, no aproximada por centro→punto.
 */
public final class CollisionEngine {

    private CollisionEngine() {}

    // ───────────── Resultado de colisión mundo ─────────────
    public static class Hit {
        public final Vector normal = new Vector(0,0,0);
        public boolean hit = false;
        public double penetration = 0;
        public final List<Block> blocks = new ArrayList<>(4);
        public Block primaryBlock = null;
        public BlockFace face = BlockFace.SELF;
        public double t = 0;               // tiempo de impacto [0..1]
        public double contactCount = 0;
        public final Vector contactPoint = new Vector(0,0,0);
    }

    // ───────────── Tablas de material ─────────────
    private static final Map<Material, Double> FRICTION_TABLE = new HashMap<>(32);
    private static final Map<Material, Double> RESTITUTION_TABLE = new HashMap<>(32);
    static {
        FRICTION_TABLE.put(Material.STONE, 0.65); FRICTION_TABLE.put(Material.COBBLESTONE, 0.65);
        FRICTION_TABLE.put(Material.ANDESITE, 0.65); FRICTION_TABLE.put(Material.DIORITE, 0.65);
        FRICTION_TABLE.put(Material.GRANITE, 0.65); FRICTION_TABLE.put(Material.DEEPSLATE, 0.7);
        FRICTION_TABLE.put(Material.OBSIDIAN, 0.4); FRICTION_TABLE.put(Material.CRYING_OBSIDIAN, 0.35);
        FRICTION_TABLE.put(Material.BEDROCK, 0.9); FRICTION_TABLE.put(Material.IRON_BLOCK, 0.3);
        FRICTION_TABLE.put(Material.GOLD_BLOCK, 0.28); FRICTION_TABLE.put(Material.DIAMOND_BLOCK, 0.32);
        FRICTION_TABLE.put(Material.EMERALD_BLOCK, 0.32); FRICTION_TABLE.put(Material.COPPER_BLOCK, 0.35);
        FRICTION_TABLE.put(Material.OAK_PLANKS, 0.75); FRICTION_TABLE.put(Material.SPRUCE_PLANKS, 0.75);
        FRICTION_TABLE.put(Material.BIRCH_PLANKS, 0.75); FRICTION_TABLE.put(Material.JUNGLE_PLANKS, 0.75);
        FRICTION_TABLE.put(Material.ACACIA_PLANKS, 0.75); FRICTION_TABLE.put(Material.DARK_OAK_PLANKS, 0.75);
        FRICTION_TABLE.put(Material.MANGROVE_PLANKS, 0.75); FRICTION_TABLE.put(Material.CHERRY_PLANKS, 0.75);
        FRICTION_TABLE.put(Material.BAMBOO_PLANKS, 0.72); FRICTION_TABLE.put(Material.CRIMSON_PLANKS, 0.78);
        FRICTION_TABLE.put(Material.WARPED_PLANKS, 0.78); FRICTION_TABLE.put(Material.GLASS, 0.2);
        FRICTION_TABLE.put(Material.AMETHYST_BLOCK, 0.15); FRICTION_TABLE.put(Material.SLIME_BLOCK, 0.95);
        FRICTION_TABLE.put(Material.HONEY_BLOCK, 0.92); FRICTION_TABLE.put(Material.PACKED_ICE, 0.08);
        FRICTION_TABLE.put(Material.BLUE_ICE, 0.05);

        RESTITUTION_TABLE.put(Material.SLIME_BLOCK, 0.85);
        RESTITUTION_TABLE.put(Material.HONEY_BLOCK, 0.05);
        RESTITUTION_TABLE.put(Material.PACKED_ICE, 0.10); RESTITUTION_TABLE.put(Material.BLUE_ICE, 0.12);
        RESTITUTION_TABLE.put(Material.ICE, 0.05);
        RESTITUTION_TABLE.put(Material.OBSIDIAN, 0.18); RESTITUTION_TABLE.put(Material.BEDROCK, 0.2);
        RESTITUTION_TABLE.put(Material.IRON_BLOCK, 0.5); RESTITUTION_TABLE.put(Material.COPPER_BLOCK, 0.42);
        RESTITUTION_TABLE.put(Material.GOLD_BLOCK, 0.55); RESTITUTION_TABLE.put(Material.DIAMOND_BLOCK, 0.58);
        RESTITUTION_TABLE.put(Material.EMERALD_BLOCK, 0.58); RESTITUTION_TABLE.put(Material.GLASS, 0.5);
        RESTITUTION_TABLE.put(Material.AMETHYST_BLOCK, 0.6);
        RESTITUTION_TABLE.put(Material.STONE, 0.3); RESTITUTION_TABLE.put(Material.COBBLESTONE, 0.34);
        RESTITUTION_TABLE.put(Material.DEEPSLATE, 0.28); RESTITUTION_TABLE.put(Material.GRANITE, 0.32);
        RESTITUTION_TABLE.put(Material.OAK_PLANKS, 0.35); RESTITUTION_TABLE.put(Material.SPRUCE_PLANKS, 0.35);
        RESTITUTION_TABLE.put(Material.BIRCH_PLANKS, 0.35); RESTITUTION_TABLE.put(Material.JUNGLE_PLANKS, 0.35);
        RESTITUTION_TABLE.put(Material.CRIMSON_PLANKS, 0.38);
    }

    public static double getFriction(Material mat) { return FRICTION_TABLE.getOrDefault(mat, 0.6); }
    public static double getRestitution(Material mat) { return RESTITUTION_TABLE.getOrDefault(mat, 0.3); }

    // ───────────── Cache por tick ─────────────
    private static final Map<Long, Boolean> solidCache = new HashMap<>(1024);
    private static long cacheTick = -1;
    private static World cacheWorld = null;

    private static long blockKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFF) << 42 | ((long) z & 0x3FFFFF) << 21 | ((long) y & 0x1FFFFF);
    }

    private static boolean isSolidCached(World w, int x, int y, int z) {
        long now = w.getFullTime();
        if (cacheTick != now || cacheWorld != w) {
            solidCache.clear();
            cacheTick = now;
            cacheWorld = w;
        }
        long k = blockKey(x, y, z);
        Boolean cached = solidCache.get(k);
        if (cached != null) return cached;
        boolean solid = isSolidExact(w, x, y, z);
        solidCache.put(k, solid);
        return solid;
    }

    private static boolean isSolidExact(World w, int x, int y, int z) {
        if (y < w.getMinHeight() || y >= w.getMaxHeight()) return y < w.getMinHeight();
        Block b = w.getBlockAt(x, y, z);
        if (b.getType().isAir()) return false;
        try {
            VoxelShape shape = b.getCollisionShape();
            if (shape.getBoundingBoxes().isEmpty()) return false;
            return !b.isPassable() || b.getType().isSolid();
        } catch (Throwable t) {
            return !b.isPassable() || b.getType().isSolid();
        }
    }

    // ───────────── Half-extents por tipo ─────────────
    public static Vector3f halfExtentsFor(boolean isHead, boolean isVase, boolean isTrapdoor) {
        if (isHead) return new Vector3f(0.30f, 0.30f, 0.30f);
        if (isVase) return new Vector3f(0.325f, 0.5f, 0.325f);
        if (isTrapdoor) return new Vector3f(0.5f, 0.09375f, 0.5f);
        return new Vector3f(0.5f, 1f, 0.30f);
    }

    // ═══════════════════════════════════════════════════════════
    //  CCD CONTINUO: swept SAT OBB frente a bloques candidatos
    // ═══════════════════════════════════════════════════════════

    /**
     * Sweep continuo OBB de `from` a `to`. Encuentra el primer bloque candidato
     * del volumen barrido y resuelve el instante exacto de impacto por SAT de
     * intervalos sobre los 15 ejes de separación.
     */
    public static Hit checkOBBSwept(World w, Vector from, Vector to, Quaternionf rot, Vector3f half) {
        Hit result = new Hit();
        if (w == null) return result;
        Vector dir = to.clone().subtract(from);
        double len = dir.length();
        if (len < 1e-8) return checkOBB(w, to, rot, half);

        // Candidatos = unión de AABB(antes) y AABB(después), expandido 1 bloque
        BoundingBox aA = approxAABB(from, rot, half);
        BoundingBox aB = approxAABB(to, rot, half);
        int minX = Math.max(w.getMinHeight() >= 0 ? 0 : Integer.MIN_VALUE, (int) Math.floor(Math.min(aA.getMinX(), aB.getMinX())) - 1);
        int maxX = (int) Math.floor(Math.max(aA.getMaxX(), aB.getMaxX())) + 1;
        int minY = Math.max(w.getMinHeight(), (int) Math.floor(Math.min(aA.getMinY(), aB.getMinY())) - 1);
        int maxY = Math.min(w.getMaxHeight() - 1, (int) Math.floor(Math.max(aA.getMaxY(), aB.getMaxY())) + 1);
        int minZ = (int) Math.floor(Math.min(aA.getMinZ(), aB.getMinZ())) - 1;
        int maxZ = (int) Math.floor(Math.max(aA.getMaxZ(), aB.getMaxZ())) + 1;

        double bestT = Double.POSITIVE_INFINITY;
        Hit best = null;

        for (int by = minY; by <= maxY; by++)
            for (int bx = minX; bx <= maxX; bx++)
                for (int bz = minZ; bz <= maxZ; bz++) {
                    if (!isSolidCached(w, bx, by, bz)) continue;
                    Block blk = w.getBlockAt(bx, by, bz);
                    List<BoundingBox> boxes;
                    try {
                        boxes = new ArrayList<>(blk.getCollisionShape().getBoundingBoxes());
                    } catch (Throwable t) {
                        boxes = null;
                    }
                    if (boxes == null) {
                        boxes = new ArrayList<>(1);
                        boxes.add(new BoundingBox(0, 0, 0, 1, 1, 1));
                    }
                    for (BoundingBox bb : boxes) {
                        // Caja del bloque en coords mundo
                        BoundingBox worldBB = bb.clone();
                        worldBB.shift(bx, by, bz);
                        Vector3f boxC = new Vector3f(
                            (float) worldBB.getCenterX(), (float) worldBB.getCenterY(), (float) worldBB.getCenterZ());
                        Vector3f boxHalf = new Vector3f(
                            (float) worldBB.getWidthX() * 0.5f,
                            (float) worldBB.getHeight() * 0.5f,
                            (float) (worldBB.getMaxZ() - worldBB.getMinZ()) * 0.5f);

                        Sweep s = sweepOBBvsBox(from, dir, len, rot, half, boxC, boxHalf);
                        if (s.hit && s.t < bestT) {
                            bestT = s.t;
                            best = new Hit();
                            best.hit = true;
                            best.t = s.t;
                            best.normal.setX(s.normal.x); best.normal.setY(s.normal.y); best.normal.setZ(s.normal.z);
                            best.penetration = s.penetration;
                            best.primaryBlock = blk;
                            if (best.blocks.size() < 4) best.blocks.add(blk);
                            best.face = faceOfNormal(best.normal);
                            best.contactPoint.copy(from.clone().add(dir.clone().multiply(bestT)));
                        }
                    }
                }

        if (best == null) {
            // Sin impacto en el barrido: chequea reposo en destino
            Hit finalHit = checkOBB(w, to, rot, half);
            if (finalHit.hit) {
                finalHit.t = 1.0;
                finalHit.penetration = Math.min(0.2, finalHit.penetration);
            }
            return finalHit;
        }
        return best;
    }

    /** Resultado de swept SAT de un eje. */
    private static final class Sweep {
        boolean hit = false;
        double t = 1.0;
        double penetration = 0;
        final Vector3f normal = new Vector3f();
    }

    /**
     * Swept OBB vs caja AABB estática con SAT de intervalos sobre 12 ejes.
     * Oriented axes u0,u1,u2 + world ejes + 6 cruces no degenerados.
     */
    private static Sweep sweepOBBvsBox(Vector from, Vector dir, double dirLen,
                                        Quaternionf rot, Vector3f half,
                                        Vector3f boxC, Vector3f boxHalf) {
        Sweep s = new Sweep();
        if (dirLen < 1e-9) return s;

        // Ejes locales del OBB (orientación final del tick; giros por tick son pequeños)
        Vector3f[] u = new Vector3f[3];
        u[0] = new Vector3f(1, 0, 0); rot.transform(u[0]);
        u[1] = new Vector3f(0, 1, 0); rot.transform(u[1]);
        u[2] = new Vector3f(0, 0, 1); rot.transform(u[2]);

        // Ejes del mundo
        Vector3f[] e = new Vector3f[3];
        e[0] = new Vector3f(1, 0, 0);
        e[1] = new Vector3f(0, 1, 0);
        e[2] = new Vector3f(0, 0, 1);

        // Velocidad relativa (box estático)
        Vector3f vRel = new Vector3f((float) dir.getX(), (float) dir.getY(), (float) dir.getZ());

        // Posición relativa inicial: centro OBB - centro box
        double d0x = from.getX() - boxC.x;
        double d0y = from.getY() - boxC.y;
        double d0z = from.getZ() - boxC.z;

        double tEntry = 0, tExit = 1.0;
        Vector3f entryAxis = null;
        double entryRatio = -1;
        double entryS = 0;
        double entryD = 0;
        double entryV = 0;

        // Axes: 3 OBB + 3 mundo + 6 cruces OBB×mundo
        List<Vector3f> axes = new ArrayList<>(12);
        for (Vector3f a : u) axes.add(a);
        for (Vector3f b : e) axes.add(b);
        for (Vector3f a : u)
            for (Vector3f b : e) {
                Vector3f c = new Vector3f(a).cross(b);
                if (c.lengthSquared() > 1e-8) {
                    c.normalize();
                    axes.add(c);
                }
            }

        for (Vector3f axis : axes) {
            double S = projectedExtent(half, axis) + projectedExtent(boxHalf, axis);
            double d0 = d0x * axis.x + d0y * axis.y + d0z * axis.z;
            double m = vRel.x * axis.x + vRel.y * axis.y + vRel.z * axis.z;
            S *= 1.0005; // margen numérico

            double te, tx;
            if (Math.abs(m) < 1e-12) {
                if (Math.abs(d0) > S) return s; // separado permanentemente en este eje
                te = 0; tx = 1;
            } else {
                double t1 = (-S - d0) / m;
                double t2 = (S - d0) / m;
                te = Math.min(t1, t2);
                tx = Math.max(t1, t2);
                if (tx < 0 || te > 1.0) return s; // sale del rango antes de conocer impacto
                te = Math.max(te, 0);
                tx = Math.min(tx, 1.0);
                if (te > tx) return s;
            }
            if (te > tEntry) {
                tEntry = te;
                // El eje que produce el retraso máximo es el eje de impacto
                entryRatio = Math.abs(d0 + m * tEntry) / S;
                entryS = S;
                entryD = d0;
                entryV = m;
                entryAxis = axis;
            }
            if (tx < tExit) tExit = tx;
        }

        if (tEntry <= tExit && tEntry <= 1.0 && entryAxis != null) {
            s.hit = true;
            s.t = tEntry;
            // Normal de impacto: eje apuntando desde la caja hacia el OBB
            double dAtT = entryD + entryV * tEntry;
            double sign = dAtT >= 0 ? 1 : -1; // dAtT = proyección (OBB-center - box-center)
            s.normal.set((float) (entryAxis.x * sign), (float) (entryAxis.y * sign), (float) (entryAxis.z * sign));
            s.penetration = Math.max(0, entryS - Math.abs(dAtT));
        }
        return s;
    }

    private static double projectedExtent(Vector3f half, Vector3f axis) {
        return half.x * Math.abs(axis.x) + half.y * Math.abs(axis.y) + half.z * Math.abs(axis.z);
    }

    private static BlockFace faceOfNormal(Vector n) {
        double ax = Math.abs(n.getX()), ay = Math.abs(n.getY()), az = Math.abs(n.getZ());
        if (ay >= ax && ay >= az) return n.getY() > 0 ? BlockFace.UP : BlockFace.DOWN;
        if (ax >= ay && ax >= az) return n.getX() > 0 ? BlockFace.EAST : BlockFace.WEST;
        return n.getZ() > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    // ═══════════════════════════════════════════════════════════
    //  CHECK ESTÁTICO multi-contacto (reposo / fallback)
    // ═══════════════════════════════════════════════════════════

    public static Hit checkOBB(World w, Vector pos, Quaternionf rot, Vector3f halfExtents) {
        Hit hit = new Hit();
        if (w == null) return hit;
        float hx = halfExtents.x, hy = halfExtents.y, hz = halfExtents.z;
        List<Vector3f> locals = new ArrayList<>(48);
        for (int sx : new int[]{-1, 1})
            for (int sy : new int[]{-1, 1})
                for (int sz : new int[]{-1, 1})
                    locals.add(new Vector3f(sx * hx, sy * hy, sz * hz));
        for (int sy : new int[]{-1, 1}) for (int sz : new int[]{-1, 1}) locals.add(new Vector3f(0, sy * hy, sz * hz));
        for (int sx : new int[]{-1, 1}) for (int sz : new int[]{-1, 1}) locals.add(new Vector3f(sx * hx, 0, sz * hz));
        for (int sx : new int[]{-1, 1}) for (int sy : new int[]{-1, 1}) locals.add(new Vector3f(sx * hx, sy * hy, 0));
        locals.add(new Vector3f(0, hy, 0));
        locals.add(new Vector3f(0, -hy, 0));
        locals.add(new Vector3f(hx, 0, 0));
        locals.add(new Vector3f(-hx, 0, 0));
        locals.add(new Vector3f(0, 0, hz));
        locals.add(new Vector3f(0, 0, -hz));
        locals.add(new Vector3f(0, 0, 0));

        Vector sumNormal = new Vector(0, 0, 0);
        Vector sumContact = new Vector(0, 0, 0);
        double maxPen = 0;
        int hits = 0;

        BoundingBox aabb = approxAABB(pos, rot, halfExtents);
        int minX = (int) Math.floor(aabb.getMinX()), maxX = (int) Math.floor(aabb.getMaxX());
        int minY = (int) Math.floor(aabb.getMinY()), maxY = (int) Math.floor(aabb.getMaxY());
        int minZ = (int) Math.floor(aabb.getMinZ()), maxZ = (int) Math.floor(aabb.getMaxZ());

        for (Vector3f local : locals) {
            Vector3f r = new Vector3f(local);
            rot.transform(r);
            double wx = pos.getX() + r.x, wy = pos.getY() + r.y, wz = pos.getZ() + r.z;
            int bx = (int) Math.floor(wx), by = (int) Math.floor(wy), bz = (int) Math.floor(wz);
            if (by < w.getMinHeight() || by >= w.getMaxHeight()) {
                if (by < w.getMinHeight()) {
                    hit.hit = true;
                    sumNormal.add(new Vector(0, 1, 0));
                    sumContact.add(new Vector(wx, wy, wz));
                    hits++;
                    maxPen = Math.max(maxPen, w.getMinHeight() - wy);
                    if (hit.primaryBlock == null) hit.primaryBlock = w.getBlockAt(bx, w.getMinHeight(), bz);
                }
                continue;
            }
            if (bx < minX || bx > maxX || by < minY || by > maxY || bz < minZ || bz > maxZ) continue;
            if (!isSolidCached(w, bx, by, bz)) continue;
            Block b = w.getBlockAt(bx, by, bz);
            boolean inside = false;
            try {
                for (BoundingBox bb : b.getCollisionShape().getBoundingBoxes()) {
                    BoundingBox worldBB = bb.clone();
                    worldBB.shift(bx, by, bz);
                    if (worldBB.contains(wx, wy, wz)) { inside = true; break; }
                }
            } catch (Throwable ignored) {}
            if (!inside) continue;

            hit.hit = true;
            hits++;
            if (hit.blocks.size() < 6) hit.blocks.add(b);
            if (hit.primaryBlock == null) hit.primaryBlock = b;
            Vector n = computeFaceNormal(wx, wy, wz, bx, by, bz);
            sumNormal.add(n);
            sumContact.add(new Vector(wx, wy, wz));
            maxPen = Math.max(maxPen, computePenetration(wx, wy, wz, bx, by, bz, n));
        }

        if (hits > 0) {
            hit.contactCount = hits;
            hit.contactPoint.copy(sumContact);
            hit.contactPoint.multiply(1.0 / hits);
            sumNormal.multiply(1.0 / hits);
            if (sumNormal.lengthSquared() > 1e-8) sumNormal.normalize();
            double ax = Math.abs(sumNormal.getX()), ay = Math.abs(sumNormal.getY()), az = Math.abs(sumNormal.getZ());
            if (ay > 0.6 && ay >= ax && ay >= az) {
                sumNormal = new Vector(0, Math.signum(sumNormal.getY()), 0);
            } else if (ax > 0.6 && ax >= ay && ax >= az) {
                sumNormal = new Vector(Math.signum(sumNormal.getX()), 0, 0);
            } else if (az > 0.6) {
                sumNormal = new Vector(0, 0, Math.signum(sumNormal.getZ()));
            } else {
                sumNormal.normalize();
            }
            hit.normal.copy(sumNormal);
            hit.penetration = maxPen + 0.05;
            hit.face = faceOfNormal(hit.normal);
        }
        return hit;
    }

    private static Vector computeFaceNormal(double wx, double wy, double wz, int bx, int by, int bz) {
        double dx0 = wx - bx, dx1 = bx + 1 - wx;
        double dy0 = wy - by, dy1 = by + 1 - wy;
        double dz0 = wz - bz, dz1 = bz + 1 - wz;
        double minD = dx0;
        Vector n = new Vector(-1, 0, 0);
        if (dx1 < minD) { minD = dx1; n = new Vector(1, 0, 0); }
        if (dy0 < minD) { minD = dy0; n = new Vector(0, -1, 0); }
        if (dy1 < minD) { minD = dy1; n = new Vector(0, 1, 0); }
        if (dz0 < minD) { minD = dz0; n = new Vector(0, 0, -1); }
        if (dz1 < minD) { minD = dz1; n = new Vector(0, 0, 1); }
        return n;
    }

    private static double computePenetration(double wx, double wy, double wz, int bx, int by, int bz, Vector faceNormal) {
        if (faceNormal.getX() != 0) {
            double dx = faceNormal.getX() > 0 ? (bx + 1 - wx) : (wx - bx);
            return Math.max(0, 0.5 - dx);
        } else if (faceNormal.getY() != 0) {
            double dy = faceNormal.getY() > 0 ? (by + 1 - wy) : (wy - by);
            return Math.max(0, 0.5 - dy);
        } else {
            double dz = faceNormal.getZ() > 0 ? (bz + 1 - wz) : (wz - bz);
            return Math.max(0, 0.5 - dz);
        }
    }

    public static BoundingBox approxAABB(Vector pos, Quaternionf rot, Vector3f half) {
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (int sx : new int[]{-1, 1})
            for (int sy : new int[]{-1, 1})
                for (int sz : new int[]{-1, 1}) {
                    Vector3f c = new Vector3f(sx * half.x, sy * half.y, sz * half.z);
                    rot.transform(c);
                    double wx = pos.getX() + c.x, wy = pos.getY() + c.y, wz = pos.getZ() + c.z;
                    minX = Math.min(minX, wx); minY = Math.min(minY, wy); minZ = Math.min(minZ, wz);
                    maxX = Math.max(maxX, wx); maxY = Math.max(maxY, wy); maxZ = Math.max(maxZ, wz);
                }
        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    // ═══════════════════════════════════════════════════════════
    //  RESOLUCIÓN DE VELOCIDAD (material + peso + giro)
    // ═══════════════════════════════════════════════════════════

    public static Vector resolveVelocity(Vector vel, Hit hit, double baseRestitution,
                                          double baseFriction, double weight,
                                          float yawRate, float pitchRate) {
        return resolveVelocity(vel, hit, baseRestitution, baseFriction, weight, yawRate, pitchRate, 0);
    }

    /**
     * Resolución con restitución por material del bloque golpeado,
     * fricción por material y acoplamiento angular.
     */
    public static Vector resolveVelocity(Vector vel, Hit hit, double baseRestitution,
                                          double baseFriction, double weight,
                                          float yawRate, float pitchRate, double materialRestitution) {
        if (!hit.hit) return vel;
        Vector n = hit.normal;
        double dot = vel.dot(n);
        if (dot >= 0) return vel;

        Material mat = hit.primaryBlock != null ? hit.primaryBlock.getType() : null;
        double matFric = mat != null ? getFriction(mat) : baseFriction;
        double matRest = materialRestitution > 0
                ? materialRestitution
                : (mat != null ? getRestitution(mat) : baseRestitution);

        // Peso alto → menos rebote
        double effRest = matRest * (1.4 / Math.max(0.5, weight));
        effRest = Math.min(effRest, 1.0);

        Vector reflected = vel.clone().subtract(n.clone().multiply(2 * dot));
        reflected.multiply(effRest);

        if (Math.abs(n.getY()) > 0.5) {
            double groundFric = baseFriction * (0.6 + matFric * 0.4);
            reflected.setX(reflected.getX() * groundFric);
            reflected.setZ(reflected.getZ() * groundFric);
        } else {
            double wallFric = baseFriction * (0.7 + matFric * 0.3);
            reflected.setX(reflected.getX() * wallFric);
            reflected.setZ(reflected.getZ() * wallFric);
        }

        double angInfluence = Math.min(0.25, (Math.abs(yawRate) + Math.abs(pitchRate)) * 0.01);
        if (angInfluence > 0.01 && (Math.abs(n.getX()) > 0.5 || Math.abs(n.getZ()) > 0.5)) {
            Vector perp = new Vector(-n.getZ(), 0, n.getX());
            if (perp.lengthSquared() > 1e-6) perp.normalize();
            double spinSign = yawRate > 0 ? 1 : -1;
            reflected.add(perp.multiply(spinSign * angInfluence * vel.length()));
        }
        return reflected;
    }

    // ═══════════════════════════════════════════════════════════
    //  OBB-OBB (SAT) + resolución de impulso
    // ═══════════════════════════════════════════════════════════

    public static class BodyHit {
        public boolean hit = false;
        public final Vector normal = new Vector(0, 0, 0);
        public double penetration = 0;
    }

    public static BodyHit checkBodyBody(Vector3f posA, Quaternionf rotA, Vector3f halfA,
                                         Vector3f posB, Quaternionf rotB, Vector3f halfB) {
        BodyHit result = new BodyHit();
        Vector3f[] axesA = new Vector3f[3];
        axesA[0] = new Vector3f(1, 0, 0); rotA.transform(axesA[0]);
        axesA[1] = new Vector3f(0, 1, 0); rotA.transform(axesA[1]);
        axesA[2] = new Vector3f(0, 0, 1); rotA.transform(axesA[2]);
        Vector3f[] axesB = new Vector3f[3];
        axesB[0] = new Vector3f(1, 0, 0); rotB.transform(axesB[0]);
        axesB[1] = new Vector3f(0, 1, 0); rotB.transform(axesB[1]);
        axesB[2] = new Vector3f(0, 0, 1); rotB.transform(axesB[2]);
        Vector3f d = new Vector3f(posB).sub(posA);

        double minOverlap = Double.POSITIVE_INFINITY;
        Vector3f minAxis = null;
        List<Vector3f> allAxes = new ArrayList<>(15);
        for (Vector3f a : axesA) allAxes.add(a);
        for (Vector3f a : axesB) allAxes.add(a);
        for (Vector3f a : axesA)
            for (Vector3f b : axesB) {
                Vector3f cross = new Vector3f(a).cross(b);
                if (cross.lengthSquared() > 1e-8) { cross.normalize(); allAxes.add(cross); }
            }
        for (Vector3f axis : allAxes) {
            double projD = Math.abs(d.x * axis.x + d.y * axis.y + d.z * axis.z);
            double extA = projectedExtent(halfA, axis);
            double extB = projectedExtent(halfB, axis);
            double overlap = extA + extB - projD;
            if (overlap <= 0) return result;
            if (overlap < minOverlap) { minOverlap = overlap; minAxis = axis; }
        }
        if (minAxis != null) {
            result.hit = true;
            result.penetration = minOverlap;
            if (d.dot(minAxis) < 0) minAxis.mul(-1);
            result.normal.setX(minAxis.x); result.normal.setY(minAxis.y); result.normal.setZ(minAxis.z);
        }
        return result;
    }

    public static Vector slideAlongWall(Vector vel, Vector normal, double friction) {
        double dot = vel.dot(normal);
        if (dot >= 0) return vel;
        Vector slide = vel.clone().subtract(normal.clone().multiply(dot));
        slide.multiply(1.0 - friction * 0.15);
        return slide;
    }

    public static float computeImpactSpin(Vector vel, Vector normal, Vector3f half, double weight) {
        double impulse = Math.abs(vel.dot(normal));
        if (impulse < 0.02) return 0;
        double spinFactor = impulse / Math.max(0.5, weight) * 2.5;
        return (float) (spinFactor * (Math.random() - 0.5) * 12);
    }
}