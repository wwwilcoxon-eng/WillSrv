#!/usr/bin/env python3
"""
XautralMusic — CLI conversor de audio a OGG Vorbis
Convierte TODOS los archivos de audio del proyecto (wav/mp3/flac/...) a .ogg vorbis perfecto
Uso local fuera del plugin, ideal antes de subir al servidor o para normalizar tu librería.

Requisitos: ffmpeg en PATH (libvorbis). Instalación:
  Ubuntu/Debian: sudo apt update && sudo apt install -y ffmpeg
  Fedora:        sudo dnf install ffmpeg
  Arch:          sudo pacman -S ffmpeg
  Windows:       winget install Gyan.FFmpeg / choco install ffmpeg
  Mac:           brew install ffmpeg

Ejemplos:
  python script.py                          # escanea . (recursivo) y convierte
  python script.py --path plugins/XautralMusic/music --quality 6
  python script.py --dry-run --verbose      # solo muestra qué haría
  python script.py --keep-original false --overwrite
  python script.py --exts wav,mp3,flac --output plugins/XautralMusic/music
  python script.py --ffmpeg /usr/bin/ffmpeg --rate 48000
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
            # ignora directorios basura
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
            # retry sin -q:a
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

def main():
    ap = argparse.ArgumentParser(description="Convierte audios del proyecto a OGG Vorbis (XautralMusic CLI)", formatter_class=argparse.RawTextHelpFormatter, epilog="Ejemplo: python script.py --path plugins/XautralMusic --quality 6 --overwrite")
    ap.add_argument("--path", default=".", help="Carpeta raíz a escanear (default: .)")
    ap.add_argument("--exts", default=",".join(DEFAULT_EXTS), help=f"Extensiones a convertir (coma separada). default: {','.join(DEFAULT_EXTS)}")
    ap.add_argument("--output", default=None, help="Carpeta salida. Si no se indica, crea .ogg junto al original (mismo nombre, ext .ogg)")
    ap.add_argument("--quality", type=int, default=5, help="Calidad vorbis 1-10 (4~128k, 5~160k, 6~192k) default 5")
    ap.add_argument("--rate", type=int, default=48000, help="Sample rate Hz (default 48000)")
    ap.add_argument("--ffmpeg", default="ffmpeg", help="Ruta a ffmpeg (default: ffmpeg en PATH)")
    ap.add_argument("--recursive", action=argparse.BooleanOptionalAction, default=True, help="Escaneo recursivo (default: sí, usa --no-recursive para solo top)")
    ap.add_argument("--keep-original", action=argparse.BooleanOptionalAction, default=False, help="Mantener original tras convertir (default: no — borra el antiguo y deja solo .ogg). Usa --keep-original para conservarlo")
    ap.add_argument("--overwrite", action="store_true", help="Sobrescribir .ogg existentes")
    ap.add_argument("--dry-run", action="store_true", help="Solo mostrar qué haría, no convierte")
    ap.add_argument("--verbose", action="store_true", help="Muestra comandos ffmpeg")
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
        # verifica que realmente existe si es ruta absoluta
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
    print(f"[XautralMusic CLI] Buscando audios en {root.resolve()} (exts={','.join(exts)}) recursive={args.recursive} ffmpeg={ffmpeg} q={args.quality} rate={args.rate}")
    audios = scan_audios(root, exts, recursive=args.recursive)
    if not audios:
        print("No se encontraron audios para convertir (solo .ogg existentes o nada).")
        print("Pon .wav/.mp3/.flac en plugins/XautralMusic/music o sfx/ y reintenta.")
        return

    print(f"Encontrados {len(audios)} archivos:")
    for a in audios[:30]:
        print(f"  - {a.relative_to(root) if a.is_relative_to(root) else a} ({a.stat().st_size//1024}KB)")
    if len(audios) > 30:
        print(f"  ... y {len(audios)-30} más")

    if args.dry_run:
        print("\n[DRY-RUN] no se convertirá nada, solo simulación")

    ok = skip = fail = 0
    for src in audios:
        # destino
        if args.output:
            out_dir = Path(args.output)
            # preserva estructura relativa
            try: rel = src.relative_to(root)
            except: rel = Path(src.name)
            dst = out_dir / rel.with_suffix(".ogg")
        else:
            dst = src.with_suffix(".ogg")
            # si src ya es .ogg no llegará aquí porque filtramos, pero por si acaso
            if dst == src:
                continue
        # evita convertir si dst == src (no)
        res = convert_one(src, dst, ffmpeg, args.quality, args.rate, args.overwrite, args.dry_run, args.verbose)
        if res == "ok" or res == "dry": ok += 1
        elif res == "skip": skip += 1
        else: fail += 1
        # borrar original si se pide
        if res == "ok" and not args.keep_original:
            try:
                src.unlink()
                print(f"  DEL original {src.name}")
            except Exception as e:
                print(f"  WARN no se pudo borrar {src}: {e}")

    print("\nResumen:")
    print(f"  Convertidos: {ok}")
    print(f"  Omitidos:    {skip}")
    print(f"  Fallidos:    {fail}")
    if fail == 0 and ok > 0 and not args.dry_run:
        print("\nListo. Haz /xm reload en el servidor para regenerar el pack.")
        print("Si convertiste en plugins/XautralMusic/music o sfx/, el pack tomará los nuevos .ogg.")
    elif args.dry_run:
        print("\nQuita --dry-run para convertir realmente.")

if __name__ == "__main__":
    main()
