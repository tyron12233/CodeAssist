#!/usr/bin/env python3
"""Stage a Swift toolchain that RUNS ON an Android device, for the Swift plugin (samples/swift-plugin): one ABI per run.

Apple publishes no Swift that runs on Android. Termux does: its `swift` package is the upstream compiler built against
bionic, with the runtime and Foundation for Android beside it. This script takes the packages pinned in packages.lock
(from our mirror, else Termux; the sha256 decides) and splits them the way an app has to carry them:

  out/<abi>/jniLibs/<abi>/  everything that is EXECUTED or mapped as code, flat, under lib*.so names:
    libswiftfrontend.so       bin/swift-frontend, the compiler, which also runs a program (-interpret, LLVM's JIT)
    lib*.so                   every shared library the compiler, the Swift runtime and Foundation load
  out/<abi>/assets/swift/   what is only READ, in the layout the compiler expects under its -sdk root:
    usr/lib/swift/            the resource directory: the standard library's and Foundation's modules, the shims,
                              clang's builtin headers (lib/swift/clang/include)
    usr/include/              the Android C headers (Termux's ndk-sysroot) and the libdispatch/Blocks headers

Since Android 10 an app may neither exec() nor map as code a file it wrote, which is why the first group has to arrive
inside the installed APK, in jniLibs. Termux links them for its own prefix, so on the way:
  - a versioned NEEDED/SONAME (libzstd.so.1) is rewritten in place to the plain name the APK carries (libz.so.1
    becomes the system's libz.so), since an APK only extracts files named lib*.so;
  - RUNPATH (Termux's /data/data/com.termux/files/usr/lib...) becomes $ORIGIN, so the libraries find each other in the
    native library directory whatever else is installed (Termux itself, for one);
  - the one text file naming Termux's prefix (lib/swift/dispatch/module.modulemap) is made relative.

Usage: python3 -I stage_swift.py <abi>      (arm64-v8a, armeabi-v7a or x86_64)
Env:   WORK (default: build/ beside this script), MIRROR_BASE, ALLOW_TERMUX=0 to use only the mirror.
"""
import io
import lzma
import os
import shutil
import stat
import struct
import sys
import tarfile
import hashlib
import urllib.request

SWIFT_VERSION = "6.4.3"
HERE = os.path.dirname(os.path.abspath(__file__))
WORK = os.environ.get("WORK", os.path.join(HERE, "build"))
DL = os.path.join(WORK, "dl")
MIRROR_BASE = os.environ.get(
    "MIRROR_BASE", f"https://github.com/tyron12233/CodeAssist/releases/download/swift-sdk-{SWIFT_VERSION}")
TERMUX_BASE = "https://packages.termux.dev/apt/termux-main"

# Android ABI -> (Termux arch, GNU triple of Termux's multilib dir, Swift target triple without the API level)
ABIS = {
    "arm64-v8a": ("aarch64", "aarch64-linux-android", "aarch64-unknown-linux-android"),
    "armeabi-v7a": ("arm", "arm-linux-androideabi", "armv7-unknown-linux-androideabi"),
    "x86_64": ("x86_64", "x86_64-linux-android", "x86_64-unknown-linux-android"),
}

# Libraries the system provides: never carried. libz.so is the platform's zlib (Termux links it as libz.so.1).
SYSTEM_LIBS = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", "libandroid.so"}
# Versioned names Termux links against, and what the APK carries them as.
RENAMES = {"libz.so.1": "libz.so", "libzstd.so.1": "libzstd.so"}
# Runtime libraries a program may load that need packages we do not carry (libxml2, libcurl), or that only tests use.
SKIP_RUNTIME = {"libFoundationXML.so", "libFoundationNetworking.so", "libXCTest.so", "libTesting.so",
                "lib_TestingInterop.so", "lib_Testing_Foundation.so", "libswiftRemoteMirror.so"}
# Parts of the resource directory nothing on the device uses: the compiler's own libraries (the closure carries the
# ones it needs), SwiftPM's manifest API, the migrator, and Embedded Swift.
# Headers of the packages that are only here for their libraries or builtin headers: clang's and LLVM's own API,
# clang-tidy, and libc++ (Swift's C++ interop is not offered yet).
SKIP_INCLUDE_DIRS = {"clang", "clang-c", "clang-tidy", "llvm", "llvm-c", "lld", "lldb", "mlir", "c++"}
SKIP_RESOURCE_DIRS = {"host", "pm", "migrator", "embedded", "_InternalSwiftScan", "_InternalSwiftStaticMirror", "clang"}


def log(msg):
    print(f"\n==> {msg}", flush=True)


def die(msg):
    print(msg, file=sys.stderr)
    sys.exit(1)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def read_lock(arch):
    entries = []
    with open(os.path.join(HERE, "packages.lock")) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            a, name, version, pool, digest = line.split()
            if a in (arch, "any"):
                entries.append((name, version, pool, digest))
    return entries


def fetch(pool, digest):
    """The .deb at [pool], downloaded once into DL and checked against [digest]."""
    name = os.path.basename(pool).replace(":", ".")
    path = os.path.join(DL, name)
    if not os.path.isfile(path) or sha256(path) != digest:
        sources = [f"{MIRROR_BASE}/{name}"]
        if os.environ.get("ALLOW_TERMUX", "1") != "0":
            sources.append(f"{TERMUX_BASE}/{pool}")
        for url in sources:
            try:
                print(f"downloading {url}", flush=True)
                with urllib.request.urlopen(url, timeout=120) as r, open(path + ".part", "wb") as out:
                    shutil.copyfileobj(r, out)
                os.replace(path + ".part", path)
                break
            except Exception as e:  # noqa: BLE001 - try the next source
                print(f"  could not download: {e}", file=sys.stderr)
        else:
            die(f"no source has {name} (tried {', '.join(sources)})")
    got = sha256(path)
    if got != digest:
        die(f"checksum mismatch for {path}: expected {digest}, got {got}")
    return path


def deb_data(path):
    """The data.tar.* member of the .deb (an `ar` archive) at [path], as bytes."""
    with open(path, "rb") as f:
        if f.read(8) != b"!<arch>\n":
            die(f"{path} is not a .deb")
        while True:
            header = f.read(60)
            if len(header) < 60:
                die(f"no data.tar in {path}")
            name = header[:16].decode().strip().rstrip("/")
            size = int(header[48:58].decode().strip())
            body = f.read(size)
            if size % 2:
                f.read(1)
            if name.startswith("data.tar"):
                return name, body


def extract(debs, root):
    """Unpack every .deb's files under Termux's prefix into [root], as one merged prefix."""
    prefix = "data/data/com.termux/files/usr/"
    for deb in debs:
        name, body = deb_data(deb)
        raw = lzma.decompress(body) if name.endswith(".xz") else body
        with tarfile.open(fileobj=io.BytesIO(raw)) as tar:
            for m in tar.getmembers():
                path = m.name[2:] if m.name.startswith("./") else m.name
                if not path.startswith(prefix):
                    continue
                rel = path[len(prefix):]
                if not rel or rel.startswith("..") or "/../" in rel:
                    continue
                target = os.path.join(root, rel)
                if m.isdir():
                    os.makedirs(target, exist_ok=True)
                elif m.issym():
                    os.makedirs(os.path.dirname(target), exist_ok=True)
                    if os.path.lexists(target):
                        os.remove(target)
                    os.symlink(m.linkname, target)
                elif m.isfile():
                    os.makedirs(os.path.dirname(target), exist_ok=True)
                    if os.path.lexists(target):
                        os.remove(target)
                    with tar.extractfile(m) as src, open(target, "wb") as dst:
                        shutil.copyfileobj(src, dst)
                    os.chmod(target, 0o755 if m.mode & 0o111 else 0o644)


# --- ELF: just enough to read and patch the dynamic section's strings -------------------------------------------

DT_NEEDED, DT_STRTAB, DT_SONAME, DT_RPATH, DT_RUNPATH = 1, 5, 14, 15, 29


class Elf:
    def __init__(self, path):
        self.path = path
        with open(path, "rb") as f:
            self.data = bytearray(f.read())
        d = self.data
        if d[:4] != b"\x7fELF" or d[5] != 1:
            raise ValueError(f"{path}: not a little-endian ELF")
        self.is64 = d[4] == 2
        if self.is64:
            phoff, = struct.unpack_from("<Q", d, 0x20)
            phentsize, phnum = struct.unpack_from("<HH", d, 0x36)
        else:
            phoff, = struct.unpack_from("<I", d, 0x1C)
            phentsize, phnum = struct.unpack_from("<HH", d, 0x2A)
        self.loads = []  # (vaddr, offset, filesz)
        self.dynamic = None  # (offset, size)
        for i in range(phnum):
            o = phoff + i * phentsize
            if self.is64:
                p_type, _flags, p_offset, p_vaddr, _paddr, p_filesz = struct.unpack_from("<IIQQQQ", d, o)
            else:
                p_type, p_offset, p_vaddr, _paddr, p_filesz = struct.unpack_from("<IIIII", d, o)
            if p_type == 1:
                self.loads.append((p_vaddr, p_offset, p_filesz))
            elif p_type == 2:
                self.dynamic = (p_offset, p_filesz)
        self.entries = []  # (tag, value)
        if self.dynamic:
            off, size = self.dynamic
            step, fmt = (16, "<qQ") if self.is64 else (8, "<iI")
            for o in range(off, off + size, step):
                tag, val = struct.unpack_from(fmt, d, o)
                if tag == 0:
                    break
                self.entries.append((tag, val))
        strtab = next((v for t, v in self.entries if t == DT_STRTAB), None)
        self.strtab = self.vaddr_to_offset(strtab) if strtab is not None else None

    def vaddr_to_offset(self, vaddr):
        for v, o, size in self.loads:
            if v <= vaddr < v + size:
                return vaddr - v + o
        raise ValueError(f"{self.path}: address {vaddr:#x} is in no segment")

    def string_at(self, index):
        start = self.strtab + index
        end = self.data.index(0, start)
        return self.data[start:end].decode()

    def strings(self, tag):
        return [self.string_at(v) for t, v in self.entries if t == tag]

    def needed(self):
        return self.strings(DT_NEEDED)

    def replace(self, tag, old, new):
        """Rewrite the [tag] string [old] as [new], in place; [new] may not be longer."""
        for t, v in self.entries:
            if t == tag and self.string_at(v) == old:
                if len(new) > len(old):
                    raise ValueError(f"{self.path}: '{new}' is longer than '{old}'")
                start = self.strtab + v
                if start > 0 and self.data[start - 1] != 0:
                    raise ValueError(f"{self.path}: '{old}' shares its bytes with another string")
                self.data[start:start + len(old)] = new.encode() + b"\0" * (len(old) - len(new))
                return True
        return False

    def save(self, path):
        with open(path, "wb") as f:
            f.write(self.data)


def is_elf(path):
    try:
        with open(path, "rb") as f:
            return f.read(4) == b"\x7fELF"
    except OSError:
        return False


# --- staging -----------------------------------------------------------------------------------------------------

def library_index(root, triple):
    """Every shared library in the merged prefix by the name a NEEDED entry uses, following symlinks."""
    index = {}
    for rel in ("lib", "lib/swift/host/compiler", f"opt/ndk-multilib/{triple}/lib"):
        base = os.path.join(root, rel)
        if not os.path.isdir(base):
            continue
        for f in sorted(os.listdir(base)):
            path = os.path.join(base, f)
            if ".so" in f and os.path.isfile(path) and f not in index:
                index[f] = os.path.realpath(path)
    return index


def closure(roots, index):
    """The libraries [roots] load, transitively; and the roots dropped because something they need is missing."""
    seen, dropped = {}, {}
    for root in roots:
        todo, found, missing = [root], {}, set()
        while todo:
            name = todo.pop()
            if name in found or name in seen:
                continue
            path = index.get(name)
            if path is None:
                missing.add(name)
                continue
            found[name] = path
            for dep in Elf(path).needed():
                # By the name Termux links it as (libzstd.so.1); the system's (libz.so.1 -> libz.so) are not carried.
                if RENAMES.get(dep, dep) not in SYSTEM_LIBS:
                    todo.append(dep)
        if missing:
            dropped[root] = sorted(missing)
        else:
            seen.update(found)
    return seen, dropped


def copy_tree_resolved(src, dst, skip_top=()):
    """Copy [src] to [dst] with every symlink replaced by what it points at (an APK cannot hold a link)."""
    os.makedirs(dst, exist_ok=True)
    for entry in sorted(os.listdir(src)):
        if entry in skip_top:
            continue
        s, d = os.path.join(src, entry), os.path.join(dst, entry)
        real = os.path.realpath(s)
        if not os.path.exists(real):
            continue  # dangling (clang's `latest` before clang is merged in, for instance)
        if os.path.isdir(real):
            os.makedirs(d, exist_ok=True)
            copy_tree_resolved(real, d)
        else:
            if real.endswith(".so") or ".so." in os.path.basename(real):
                continue  # code goes to jniLibs, never into assets
            shutil.copyfile(real, d)


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in ABIS:
        die(f"usage: stage_swift.py <{'|'.join(ABIS)}>")
    abi = sys.argv[1]
    arch, triple, target = ABIS[abi]
    os.makedirs(DL, exist_ok=True)

    log(f"Swift {SWIFT_VERSION} for android {abi} (Termux arch {arch})")
    debs = [fetch(pool, digest) for _name, _version, pool, digest in read_lock(arch)]

    root = os.path.join(WORK, f"extract-{arch}")
    log(f"unpacking {len(debs)} packages")
    shutil.rmtree(root, ignore_errors=True)
    os.makedirs(root)
    extract(debs, root)

    out = os.path.join(WORK, "out", abi)
    jni = os.path.join(out, "jniLibs", abi)  # the shape AGP reads: <jniLibs dir>/<abi>/lib*.so
    assets = os.path.join(out, "assets", "swift", "usr")
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(jni)
    os.makedirs(assets)

    log("libraries")
    index = library_index(root, triple)
    runtime_dir = os.path.join(root, f"opt/ndk-multilib/{triple}/lib")
    runtime = sorted(f for f in os.listdir(runtime_dir) if f.endswith(".so") and f not in SKIP_RUNTIME)
    frontend = os.path.join(root, "bin/swift-frontend")
    index["swift-frontend"] = os.path.realpath(frontend)
    # Libraries behind headers the Android module map offers (glob, System V IPC): a program using one loads it.
    # Not libiconv.so: it is LGPL, and bionic has iconv itself from API 28; its header stays for the module map.
    extras = ["libandroid-glob.so", "libandroid-sysv-semaphore.so", "libandroid-shmem.so"]
    libs, dropped = closure(["swift-frontend"] + runtime + extras, index)
    if "swift-frontend" in dropped:
        die(f"the compiler needs libraries no staged package has: {dropped['swift-frontend']}")
    for name, missing in sorted(dropped.items()):
        print(f"  left out {name}: needs {', '.join(missing)}")

    total = 0
    for name, path in sorted(libs.items()):
        dest_name = "libswiftfrontend.so" if name == "swift-frontend" else RENAMES.get(name, name)
        if not (dest_name.startswith("lib") and dest_name.endswith(".so")):
            die(f"{name} would not survive APK packaging (only lib*.so is extracted)")
        elf = Elf(path)
        for old, new in RENAMES.items():
            elf.replace(DT_NEEDED, old, new)
            elf.replace(DT_SONAME, old, new)
        for tag in (DT_RUNPATH, DT_RPATH):
            for value in elf.strings(tag):
                elf.replace(tag, value, "$ORIGIN")
        dest = os.path.join(jni, dest_name)
        elf.save(dest)
        os.chmod(dest, 0o755)
        total += os.path.getsize(dest)
        left = [n for n in Elf(dest).needed() if n not in SYSTEM_LIBS and n not in
                {RENAMES.get(k, k) for k in libs} and n != "libswiftfrontend.so"]
        if left:
            die(f"{dest_name} still needs {left}")
    print(f"  {len(libs)} libraries, {total / 1e6:.0f} MB")

    log("resource directory, headers")
    res = os.path.join(assets, "lib", "swift")
    os.makedirs(res)
    copy_tree_resolved(os.path.join(root, "lib/swift"), res, skip_top=SKIP_RESOURCE_DIRS)
    # Clang's builtin headers (stddef.h, ...), which Glibc-style imports of bionic need. Termux links them in from
    # its clang package as lib/swift/clang -> ../clang/latest.
    clang_versions = sorted(os.listdir(os.path.join(root, "lib/clang")))
    clang_include = os.path.join(root, "lib/clang", [v for v in clang_versions if v != "latest"][-1], "include")
    shutil.copytree(clang_include, os.path.join(res, "clang", "include"), symlinks=False)
    # API digester and doc files: nothing on the device reads them.
    for dirpath, _dirs, files in os.walk(res):
        for f in files:
            if f.endswith(".abi.json"):
                os.remove(os.path.join(dirpath, f))
    modulemap = os.path.join(res, "dispatch", "module.modulemap")
    with open(modulemap) as f:
        text = f.read()
    text = text.replace("/data/data/com.termux/files/usr/include/", "../../../include/")
    with open(modulemap, "w") as f:
        f.write(text)

    include = os.path.join(assets, "include")
    copy_tree_resolved(os.path.join(root, "include"), include, skip_top=SKIP_INCLUDE_DIRS)

    # Nothing may still point into Termux's prefix: the app runs from somewhere else.
    for dirpath, _dirs, files in os.walk(assets):
        for f in files:
            p = os.path.join(dirpath, f)
            # Header text may mention it (Termux patches defaults like _PATH_TMP into bionic's); a modulemap's
            # paths are resolved, so one naming Termux's prefix would point at nothing.
            if f.endswith(".modulemap"):
                with open(p, "rb") as fh:
                    if b"/data/data/com.termux" in fh.read():
                        die(f"{p} still names Termux's prefix")

    with open(os.path.join(out, "SWIFT_VERSION"), "w") as f:
        f.write(SWIFT_VERSION + "\n")
    with open(os.path.join(out, "TARGET"), "w") as f:
        f.write(target + "\n")
    size = sum(os.path.getsize(os.path.join(d, f)) for d, _s, fs in os.walk(assets) for f in fs)
    log(f"done: {out} (jniLibs {total / 1e6:.0f} MB, assets {size / 1e6:.0f} MB)")


if __name__ == "__main__":
    main()
