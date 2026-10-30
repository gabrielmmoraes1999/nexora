"""Integration checks for the Windows in-process launcher (JDK 21+, built dist/java)."""
import base64
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
JDK = Path(os.environ['JAVA_HOME']) / 'bin'
CSC = Path(os.environ['SystemRoot']) / 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'


def run(command, **kwargs):
    result = subprocess.run([str(arg) for arg in command], capture_output=True, **kwargs)
    if result.returncode:
        raise RuntimeError((result.stdout + result.stderr).decode(errors='replace'))
    return result


with tempfile.TemporaryDirectory(prefix='launcher checks ', dir=ROOT / 'dist') as directory:
    work = Path(directory)
    # The production launcher resolves java beside its executable.
    exe_dir = ROOT / 'dist'
    source = work / 'LauncherProbe.java'
    source.write_text(r'''
import java.util.Base64;
import java.nio.charset.StandardCharsets;
public class LauncherProbe {
    public static void main(String[] args) throws Exception {
        System.out.println("PID=" + ProcessHandle.current().pid());
        for (String arg : args)
            System.out.println("ARG=" + Base64.getEncoder().encodeToString(arg.getBytes(StandardCharsets.UTF_8)));
        if (Thread.currentThread().getContextClassLoader() != ClassLoader.getSystemClassLoader())
            throw new IllegalStateException("Missing application context class loader");
        System.err.println("STDERR");
        if (args.length > 0 && args[0].equals("exit")) System.exit(7);
        if (args.length > 0 && args[0].equals("throw")) throw new IllegalStateException("probe exception");
        new Thread(() -> {
            try { Thread.sleep(200); } catch (InterruptedException e) { throw new RuntimeException(e); }
            System.out.println("THREAD_FINISHED");
        }).start();
    }
}
''', encoding='utf-8')
    run([JDK / 'javac.exe', '-encoding', 'UTF-8', '-d', work, source])
    jar = work / 'probe.jar'
    with zipfile.ZipFile(jar, 'w') as archive:
        archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\nMain-Class: LauncherProbe\r\n\r\n')
        archive.write(work / 'LauncherProbe.class', 'LauncherProbe.class')
    arguments = ['', 'a b', 'ação 日本 😀', 'quote"inside', 'trailing\\']
    for mode in ('console', 'HYBRID', 'WINDOW'):
        exe = exe_dir / ('launcher-probe-' + mode + '.exe')
        command = [CSC, '/nologo', '/platform:x64', '/reference:System.IO.Compression.dll',
                   '/reference:System.IO.Compression.FileSystem.dll', '/out:' + str(exe),
                   '/resource:' + str(jar) + ',AppJar']
        command += ['/target:exe'] if mode == 'console' else [
            '/target:winexe', '/define:' + mode, '/reference:System.Windows.Forms.dll']
        try:
            run(command + [ROOT / 'packaging/SeaDBLauncher.cs'])
            process = subprocess.Popen([str(exe), *arguments], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                       creationflags=subprocess.CREATE_NO_WINDOW)
            try:
                out, err = process.communicate(timeout=30)
            except subprocess.TimeoutExpired:
                process.kill()
                process.communicate()
                raise
            assert process.returncode == 0, (mode, process.returncode, out, err)
            lines = out.decode('ascii').splitlines()
            assert lines[0] == 'PID=' + str(process.pid), (mode, lines)
            assert lines[1:1 + len(arguments)] == ['ARG=' + base64.b64encode(a.encode()).decode() for a in arguments], lines
            assert lines[-1] == 'THREAD_FINISHED', (mode, lines)
            assert b'STDERR' in err, (mode, err)
            result = subprocess.run([str(exe), 'exit'], capture_output=True, timeout=30,
                                    creationflags=subprocess.CREATE_NO_WINDOW)
            assert result.returncode == 7, (mode, result.returncode)
            if mode != 'WINDOW':
                result = subprocess.run([str(exe), 'throw'], capture_output=True, timeout=30,
                                        creationflags=subprocess.CREATE_NO_WINDOW)
                assert result.returncode == 1 and b'probe exception' in result.stderr, (mode, result)
            print(mode + ': same PID, Unicode/quoted/empty args, stdout/stderr, non-daemon lifetime, exit codes OK')
        finally:
            exe.unlink(missing_ok=True)
