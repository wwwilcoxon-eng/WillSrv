#!/usr/bin/env python3
"""
XautralMusic — CLI conversor y optimizador de audio a OGG Vorbis
Convierte TODOS los archivos de audio del proyecto (wav/mp3/flac/...) a .ogg vorbis
y OPTIMIZA los .ogg existentes para que pesen lo mínimo sin perder calidad audible.

Requisitos: ffmpeg en PATH (libvorbis). Instalación:
  Ubuntu/Debian: sudo apt update && sudo apt install -y ffmpeg
  Fedora:        sudo dnf install ffmpeg
  Arch:          sudo pacman -S ffmpeg
  Windows:       winget install Gyan.FFmpeg / choco install ffmpeg
  Mac:           brew install ffmpeg

Ejemplos:
  python script.py                                    # convierte + optimiza .ogg existentes (q=3, 44.1kHz)
  python script.py --optimize-ogg --ogg-quality 3     # solo optimiza .ogg
  python script.py --path plugins/XautralMusic/music --quality 5
  python script.py --dry-run --verbose                # solo muestra qué haría
  python script.py --keep-original false --overwrite
  python script.py --rate 44100 --ogg-quality 3
  python script.py --no-optimize-ogg                  # solo convierte no-ogg, no toca .ogg existentes
"""
import argparse
import subprocess
import sys
import shutil
from pathlib import Path

# Extensiones consideradas audio (sin punto, lower)
DEFAULT_EXTS = ["wav","wave","mp3","mpga","flac","aiff","aif","aifc","m4a","aac","wma","opus","oga","pcm","au","3gp","mkv","mp4"]
OGG_EXTS = {"ogg","oga","opus"}

# carpetas a ignorar
IGNORE_DIRS = {".git", "target", "build", ".idea", ".vscode", "__pycache__", "node_modules", "libraries", "cache", "world", "world_nether", "world_the_end", "logs", "generated"}

def find_ffmpeg(custom):
    if custom and Path(custom).exists(): return custom
    if custom: return custom
    p = shutil.which("ffmpeg")
    return p

def scan_audios(root: Path, exts, recursive=True):
    exts = {e.lower().lstrip(".") for e in exts}
    files = []
    if recursive:
        for p in root.rglob("*"):
            if any(part in IGNORE_DIRS for part in p.parts):
                continue
            if p.is_file():
                ext = p.suffix.lower().lstrip(".")
                if ext in exts and ext not in OGG_EXTS:
                    files.append(p)
    else:
        for p in root.iterdir():
            if p.is_file():
                ext = p.suffix.lower().lstrip(".")
                if ext in exts and ext not in OGG_EXTS:
                    files.append(p)
    return sorted(files)

def scan_oggs(root: Path, recursive=True):
    files = []
    if recursive:
        for p in root.rglob("*"):
            if any(part in IGNORE_DIRS for part in p.parts):
                continue
            if p.is_file():
                ext = p.suffix.lower().lstrip(".")
                if ext in OGG_EXTS:
                    # ignora generados / tmp
                    if "generated" in p.parts or "tmp" in p.parts:
                        continue
                    files.append(p)
    else:
        for p in root.iterdir():
            if p.is_file() and p.suffix.lower().lstrip(".") in OGG_EXTS:
                files.append(p)
    return sorted(files)

def convert_one(src: Path, dst: Path, ffmpeg: str, quality: int, rate: int, overwrite: bool, dry_run: bool, verbose: bool):
    if dst.exists() and not overwrite:
        if verbose: print(f"  SKIP (existe, --overwrite false): {dst}")
        return "skip"
    if dry_run:
        print(f"  [DRY] {src} -> {dst} (q={quality} rate={rate})")
        return "dry"
    dst.parent.mkdir(parents=True, exist_ok=True)
    cmd = [ffmpeg, "-y", "-i", str(src), "-c:a", "libvorbis", "-q:a", str(quality), "-ar", str(rate), str(dst)]
    if verbose: print(f"  CMD: {' '.join(cmd)}")
    try:
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode == 0 and dst.exists() and dst.stat().st_size > 0:
            print(f"  OK  {src.name} ({src.stat().st_size//1024}KB) -> {dst.name} ({dst.stat().st_size//1024}KB)")
            return "ok"
        else:
            if verbose: print(f"  WARN ffmpeg q:a falló, reintento sin calidad: {r.stderr[:300]}")
            cmd2 = [ffmpeg, "-y", "-i", str(src), "-c:a", "libvorbis", str(dst)]
            r2 = subprocess.run(cmd2, capture_output=True, text=True)
            if r2.returncode == 0 and dst.exists():
                print(f"  OK* {src.name} -> {dst.name} (fallback)")
                return "ok"
            print(f"  FAIL {src} -> {r.stderr[:400]}")
            return "fail"
    except FileNotFoundError:
        print(f"  ERROR: ffmpeg no encontrado en '{ffmpeg}'. Instala ffmpeg o usa --ffmpeg /ruta/ffmpeg")
        sys.exit(2)
    except Exception as e:
        print(f"  ERROR {src}: {e}")
        return "fail"

def optimize_one(src: Path, ffmpeg: str, quality: int, rate: int, dry_run: bool, verbose: bool, keep_larger: bool = True):
    """Re-comprime un .ogg existente in-place con máxima eficiencia.
    Usa libvorbis -q:a <quality> -ar <rate> . Reemplaza solo si el nuevo es más pequeño.
    quality 3 ≈112kbps (óptimo peso/calidad), 2≈96k, 4≈128k. 3 es el sweet spot transparente."""
    orig_size = src.stat().st_size
    if orig_size == 0:
        print(f"  SKIP vacío: {src}")
        return "skip"
    if dry_run:
        print(f"  [DRY-OPT] {src} ({orig_size//1024}KB) -> q={quality} rate={rate} (reemplazo in-place)")
        return "dry"
    tmp = src.with_suffix(".tmp.ogg")
    # -map_metadata 0 preserva tags, -vn no video
    cmd = [ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-i", str(src), "-c:a", "libvorbis", "-q:a", str(quality), "-ar", str(rate), "-vn", str(tmp)]
    if verbose: print(f"  CMD: {' '.join(cmd)}")
    try:
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode != 0 or not tmp.exists() or tmp.stat().st_size == 0:
            print(f"  FAIL opt {src.name}: ffmpeg error {r.stderr[:300] if r.stderr else 'no output'}")
            if tmp.exists(): tmp.unlink(missing_ok=True)
            return "fail"
        new_size = tmp.stat().st_size
        saving = 100 * (1 - new_size / orig_size) if orig_size else 0
        # Si el nuevo es más grande, no reemplaza (a menos que --overwrite fuerce)
        if keep_larger and new_size >= orig_size:
            print(f"  SKIP {src.name} ({orig_size//1024}KB -> {new_size//1024}KB, {saving:+.1f}%) — no ahorra, se conserva original")
            tmp.unlink(missing_ok=True)
            return "skip"
        # Reemplazo atómico
        tmp.replace(src)
        print(f"  OPT  {src.name}: {orig_size//1024}KB -> {new_size//1024}KB ({saving:+.1f}% ahorro) q={quality} {rate}Hz")
        return "ok"
    except Exception as e:
        print(f"  ERROR opt {src}: {e}")
        if tmp.exists(): tmp.unlink(missing_ok=True)
        return "fail"

def main():
    ap = argparse.ArgumentParser(description="Convierte audios del proyecto a OGG Vorbis + optimiza .ogg existentes (XautralMusic CLI)", formatter_class=argparse.RawTextHelpFormatter, epilog="Ejemplo: python script.py --path plugins/XautralMusic --quality 5 --ogg-quality 3 --overwrite")
    ap.add_argument("--path", default=".", help="Carpeta raíz a escanear (default: .)")
    ap.add_argument("--exts", default=",".join(DEFAULT_EXTS), help=f"Extensiones a convertir (coma separada). default: {','.join(DEFAULT_EXTS)}")
    ap.add_argument("--output", default=None, help="Carpeta salida. Si no se indica, crea .ogg junto al original (mismo nombre, ext .ogg)")
    ap.add_argument("--quality", type=int, default=5, help="Calidad vorbis para CONVERSIÓN 1-10 (4~128k, 5~160k, 6~192k) default 5")
    ap.add_argument("--ogg-quality", type=int, default=3, help="Calidad vorbis para OPTIMIZACIÓN de .ogg existentes 1-10 (3~112k óptimo peso/calidad, 2~96k ultra-ligero) default 3")
    ap.add_argument("--rate", type=int, default=48000, help="Sample rate Hz para conversión (default 48000)")
    ap.add_argument("--ogg-rate", type=int, default=44100, help="Sample rate Hz para optimización .ogg (default 44100 — 44.1kHz es transparente y 8%% más pequeño que 48k)")
    ap.add_argument("--ffmpeg", default="ffmpeg", help="Ruta a ffmpeg (default: ffmpeg en PATH)")
    ap.add_argument("--recursive", action=argparse.BooleanOptionalAction, default=True, help="Escaneo recursivo (default: sí, usa --no-recursive para solo top)")
    ap.add_argument("--keep-original", action=argparse.BooleanOptionalAction, default=False, help="Mantener original tras convertir (default: no — borra el antiguo y deja solo .ogg). Usa --keep-original para conservarlo")
    ap.add_argument("--overwrite", action="store_true", help="Sobrescribir .ogg existentes (también fuerza reemplazo aunque el optimizado pese más)")
    ap.add_argument("--dry-run", action="store_true", help="Solo mostrar qué haría, no convierte")
    ap.add_argument("--verbose", action="store_true", help="Muestra comandos ffmpeg")
    ap.add_argument("--optimize-ogg", action=argparse.BooleanOptionalAction, default=True, help="Optimizar .ogg existentes in-place (default: sí — usa --no-optimize-ogg para desactivar)")
    ap.add_argument("--delete-ignored", action="store_true", help="Borra .ogg huérfanos sin fuente (no usado)")
    args = ap.parse_args()

    ffmpeg = find_ffmpeg(args.ffmpeg)
    if not ffmpeg:
        print("ERROR: ffmpeg no encontrado. Instala ffmpeg:")
        print("  Ubuntu: sudo apt update && sudo apt install -y ffmpeg")
        print("  Windows: winget install Gyan.FFmpeg")
        print("  Mac: brew install ffmpeg")
        sys.exit(2)
    else:
        if "/" in ffmpeg or "\\" in ffmpeg:
            if not Path(ffmpeg).exists():
                print(f"ERROR: ffmpeg no existe en {ffmpeg}")
                sys.exit(2)
        else:
            if not shutil.which(ffmpeg):
                print(f"ERROR: ffmpeg '{ffmpeg}' no está en PATH")
                sys.exit(2)

    root = Path(args.path)
    if not root.exists():
        print(f"ERROR: path no existe: {root}")
        sys.exit(1)

    exts = [e.strip() for e in args.exts.split(",") if e.strip()]
    print(f"[XautralMusic CLI] Buscando en {root.resolve()} | exts={','.join(exts)} recursive={args.recursive} ffmpeg={ffmpeg}")
    print(f"  Conversión: q={args.quality} rate={args.rate} | Optimización .ogg: {'ON' if args.optimize_ogg else 'OFF'} q={args.ogg_quality} rate={args.ogg_rate}")

    # 1) Conversión de no-ogg -> ogg
    audios = scan_audios(root, exts, recursive=args.recursive)
    oggs = scan_oggs(root, recursive=args.recursive) if args.optimize_ogg else []

    if not audios and not oggs:
        print("No se encontraron audios para convertir ni .ogg para optimizar.")
        print("Pon .wav/.mp3/.flac en plugins/XautralMusic/music o sfx/ y reintenta, o usa --optimize-ogg para comprimir .ogg existentes.")
        return

    if audios:
        print(f"\n[1/2] Conversión: {len(audios)} archivos no-ogg encontrados:")
        for a in audios[:30]:
            print(f"  - {a.relative_to(root) if a.is_relative_to(root) else a} ({a.stat().st_size//1024}KB)")
        if len(audios) > 30:
            print(f"  ... y {len(audios)-30} más")
    else:
        print("\n[1/2] Conversión: nada que convertir (todo ya es .ogg)")

    if oggs:
        total_ogg_kb = sum(p.stat().st_size for p in oggs)//1024
        print(f"\n[2/2] Optimización: {len(oggs)} .ogg existentes ({total_ogg_kb}KB total) -> q={args.ogg_quality} {args.ogg_rate}Hz")
        for o in oggs[:30]:
            print(f"  - {o.relative_to(root) if o.is_relative_to(root) else o} ({o.stat().st_size//1024}KB)")
        if len(oggs) > 30:
            print(f"  ... y {len(oggs)-30} más")
    else:
        print("\n[2/2] Optimización: desactivada o no hay .ogg")

    if args.dry_run:
        print("\n[DRY-RUN] no se modificará nada, solo simulación")

    # Ejecuta conversión
    ok = skip = fail = 0
    for src in audios:
        if args.output:
            out_dir = Path(args.output)
            try: rel = src.relative_to(root)
            except: rel = Path(src.name)
            dst = out_dir / rel.with_suffix(".ogg")
        else:
            dst = src.with_suffix(".ogg")
            if dst == src:
                continue
        res = convert_one(src, dst, ffmpeg, args.quality, args.rate, args.overwrite, args.dry_run, args.verbose)
        if res == "ok" or res == "dry": ok += 1
        elif res == "skip": skip += 1
        else: fail += 1
        if res == "ok" and not args.keep_original:
            try:
                src.unlink()
                print(f"  DEL original {src.name}")
            except Exception as e:
                print(f"  WARN no se pudo borrar {src}: {e}")

    print(f"\n  Conversión -> ok:{ok} skip:{skip} fail:{fail}")

    # Si se convirtieron archivos, re-escanea oggs para optimizar los recién creados en el mismo run
    if ok > 0 and args.optimize_ogg:
        oggs = scan_oggs(root, recursive=args.recursive)

    # Ejecuta optimización in-place
    ok2 = skip2 = fail2 = 0
    saved_kb = 0
    if oggs:
        for ogg in oggs:
            before = ogg.stat().st_size if not args.dry_run else 0
            # Si el ogg acaba de ser creado por conversión en el mismo run, ya está optimizado; sáltarlo si dst==ogg
            # Evita re-optimizar el recién convertido (ya está a q=args.quality). Solo optimiza los pre-existentes.
            # Detecta si este ogg corresponde a un src convertido: si dst existe y src fue convertido
            is_newly_converted = any(src.with_suffix(".ogg") == ogg for src in audios) and not args.output
            if is_newly_converted and not args.optimize_ogg:
                continue
            # Si el ogg fue recién creado con q=5, re-optimizar a q=3 sí ahorra — lo permitimos
            res = optimize_one(ogg, ffmpeg, args.ogg_quality, args.ogg_rate, args.dry_run, args.verbose, keep_larger=not args.overwrite)
            if res == "ok":
                ok2 += 1
                if not args.dry_run:
                    after = ogg.stat().st_size
                    saved_kb += (before - after)//1024
            elif res == "skip": skip2 += 1
            elif res == "dry": ok2 += 1
            else: fail2 += 1
        print(f"  Optimización -> ok:{ok2} skip:{skip2} fail:{fail2} | ahorro total: {saved_kb}KB")

    total_ok = ok + ok2
    total_fail = fail + fail2
    print("\nResumen:")
    print(f"  Convertidos: {ok} | Optimizados: {ok2} | Omitidos: {skip+skip2} | Fallidos: {total_fail}")
    if saved_kb > 0:
        print(f"  Ahorro por optimización: {saved_kb}KB ({saved_kb/1024:.2f}MB)")
    if total_fail == 0 and total_ok > 0 and not args.dry_run:
        print("\nListo. Haz /xm reload en el servidor para regenerar el pack.")
        print("Si optimizaste .ogg en plugins/XautralMusic/music o sfx/, el pack tomará los nuevos .ogg más ligeros.")
    elif args.dry_run:
        print("\nQuita --dry-run para aplicar realmente.")

if __name__ == "__main__":
    main()
