using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using Microsoft.Win32.SafeHandles;
#if WINDOW || HYBRID
using System.Windows.Forms;
#endif

// Launcher compiled by compile-exe.cmd. The JAR is a Win32 resource named AppJar.
// Every tool sits next to the same java directory and starts its java.exe.
// The JVM can only open a real file, so the resource is written under %TEMP%\SeaDB
// for the run and deleted when this process exits.

internal static class SeaDBLauncher
{
    private const int JobObjectExtendedLimitInformation = 9;
    private const uint JobObjectLimitKillOnJobClose = 0x2000;
    private const int AttachParentProcess = -1;
    private const int StdOutputHandle = -11;
    private const uint GenericWrite = 0x40000000;
    private const uint OpenExisting = 3;

    private static bool visual;

    [STAThread]
    private static int Main(string[] args)
    {
#if HYBRID
        Stream cliOut = null;
        visual = args.Length == 0;
        if (!visual)
        {
            cliOut = OpenCliOutput();
        }
#endif
        string exePath = Assembly.GetExecutingAssembly().Location;
        string exeDir = Path.GetDirectoryName(exePath);
        string appName = Path.GetFileNameWithoutExtension(exePath);
#if WINDOW
        string javaName = "javaw.exe";
#elif HYBRID
        string javaName = visual ? "javaw.exe" : "java.exe";
#else
        string javaName = "java.exe";
#endif
        string javaExe = Path.Combine(exeDir, "java", "bin", javaName);
        if (!File.Exists(javaExe))
        {
            Fail(appName, "Java runtime not found:\n" + javaExe);
            return 1;
        }

        Stream resource = Assembly.GetExecutingAssembly().GetManifestResourceStream("AppJar");
        if (resource == null)
        {
            Fail(appName, "Application data is missing from the executable.");
            return 1;
        }

        string dir = Path.Combine(Path.GetTempPath(), "SeaDB");
        Directory.CreateDirectory(dir);
        DeleteStaleJars(dir, appName);
        string jarPath = Path.Combine(dir, appName + "-" + Process.GetCurrentProcess().Id + ".jar");

        using (resource)
        using (FileStream jar = new FileStream(jarPath, FileMode.Create, FileAccess.Write, FileShare.Read))
        {
            resource.CopyTo(jar);
        }

        try
        {
            ProcessStartInfo start = new ProcessStartInfo();
            start.FileName = javaExe;
            start.UseShellExecute = false;
            start.Arguments = "-jar " + Quote(jarPath);
            string quotedArgs = QuoteAll(args);
            if (quotedArgs.Length > 0)
            {
                start.Arguments = start.Arguments + " " + quotedArgs;
            }
#if HYBRID
            if (cliOut != null)
            {
                start.CreateNoWindow = true;
                start.RedirectStandardOutput = true;
                start.RedirectStandardError = true;
            }
#endif

            IntPtr job = CreateJobObject(IntPtr.Zero, null);
            Process process = Process.Start(start);
            if (process == null)
            {
                Fail(appName, "Could not start the Java runtime.");
                return 1;
            }
            if (job != IntPtr.Zero)
            {
                AssignKillOnClose(job, process);
            }
            Thread outputCopy = null;
            Thread errorCopy = null;
#if HYBRID
            if (cliOut != null)
            {
                Stream stdout = process.StandardOutput.BaseStream;
                Stream stderr = process.StandardError.BaseStream;
                outputCopy = new Thread(() => Copy(stdout, cliOut));
                errorCopy = new Thread(() => Copy(stderr, cliOut));
                outputCopy.IsBackground = true;
                errorCopy.IsBackground = true;
                outputCopy.Start();
                errorCopy.Start();
            }
#endif
            process.WaitForExit();
#if HYBRID
            if (outputCopy != null)
            {
                outputCopy.Join();
            }
            if (errorCopy != null)
            {
                errorCopy.Join();
            }
            if (cliOut != null)
            {
                cliOut.Flush();
            }
#endif
            int code = process.ExitCode;
            process.Dispose();
            if (job != IntPtr.Zero)
            {
                CloseHandle(job);
            }
            return code;
        }
        finally
        {
            try
            {
                File.Delete(jarPath);
            }
            catch (IOException)
            {
            }
            catch (UnauthorizedAccessException)
            {
            }
        }
    }

    private static void DeleteStaleJars(string dir, string appName)
    {
        string[] oldJars;
        try
        {
            oldJars = Directory.GetFiles(dir, appName + "-*.jar");
        }
        catch (IOException)
        {
            return;
        }
        for (int i = 0; i < oldJars.Length; i++)
        {
            try
            {
                File.Delete(oldJars[i]);
            }
            catch (IOException)
            {
            }
            catch (UnauthorizedAccessException)
            {
            }
        }
    }

    private static void AssignKillOnClose(IntPtr job, Process process)
    {
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION info = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
        info.BasicLimitInformation.LimitFlags = JobObjectLimitKillOnJobClose;
        int length = Marshal.SizeOf(typeof(JOBOBJECT_EXTENDED_LIMIT_INFORMATION));
        IntPtr pointer = Marshal.AllocHGlobal(length);
        try
        {
            Marshal.StructureToPtr(info, pointer, false);
            if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, pointer, (uint)length))
            {
                return;
            }
            AssignProcessToJobObject(job, process.Handle);
        }
        finally
        {
            Marshal.FreeHGlobal(pointer);
        }
    }

    private static string QuoteAll(string[] args)
    {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < args.Length; i++)
        {
            if (i > 0)
            {
                builder.Append(' ');
            }
            builder.Append(Quote(args[i]));
        }
        return builder.ToString();
    }

    private static string Quote(string value)
    {
        StringBuilder builder = new StringBuilder();
        builder.Append('"');
        int slashes = 0;
        for (int i = 0; i < value.Length; i++)
        {
            char c = value[i];
            if (c == '\\')
            {
                slashes++;
                continue;
            }
            if (c == '"')
            {
                builder.Append('\\', (slashes * 2) + 1);
                builder.Append('"');
                slashes = 0;
                continue;
            }
            if (slashes > 0)
            {
                builder.Append('\\', slashes);
                slashes = 0;
            }
            builder.Append(c);
        }
        if (slashes > 0)
        {
            builder.Append('\\', slashes * 2);
        }
        builder.Append('"');
        return builder.ToString();
    }

    private static Stream OpenCliOutput()
    {
        IntPtr stdout = GetStdHandle(StdOutputHandle);
        uint kind = 0;
        if (stdout != IntPtr.Zero && stdout.ToInt64() != -1)
        {
            kind = GetFileType(stdout);
        }
        if (kind == 1 || kind == 2 || kind == 3)
        {
            return new FileStream(new SafeFileHandle(stdout, false), FileAccess.Write);
        }
        if (!AttachConsole(AttachParentProcess))
        {
            return null;
        }
        IntPtr console = CreateFile("CONOUT$", GenericWrite, 3, IntPtr.Zero, OpenExisting, 0, IntPtr.Zero);
        if (console == IntPtr.Zero || console.ToInt64() == -1)
        {
            return null;
        }
        return new FileStream(new SafeFileHandle(console, true), FileAccess.Write);
    }

    private static void Copy(Stream input, Stream output)
    {
        byte[] buffer = new byte[256];
        try
        {
            int read;
            while ((read = input.Read(buffer, 0, buffer.Length)) > 0)
            {
                lock (output)
                {
                    output.Write(buffer, 0, read);
                    output.Flush();
                }
            }
        }
        catch (IOException)
        {
        }
    }

    private static void Fail(string appName, string message)
    {
#if WINDOW
        MessageBox.Show(message, appName, MessageBoxButtons.OK, MessageBoxIcon.Error);
#elif HYBRID
        MessageBox.Show(message, appName, MessageBoxButtons.OK, MessageBoxIcon.Error);
#else
        Console.Error.WriteLine(message);
#endif
    }

    [DllImport("kernel32.dll")]
    private static extern IntPtr GetStdHandle(int handle);

    [DllImport("kernel32.dll")]
    private static extern uint GetFileType(IntPtr handle);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool AttachConsole(int processId);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateFile(string name, uint access, uint share, IntPtr security, uint disposition, uint flags, IntPtr template);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateJobObject(IntPtr attributes, string name);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool SetInformationJobObject(IntPtr job, int infoClass, IntPtr info, uint length);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool CloseHandle(IntPtr handle);

    [StructLayout(LayoutKind.Sequential)]
    private struct JOBOBJECT_BASIC_LIMIT_INFORMATION
    {
        public long PerProcessUserTimeLimit;
        public long PerJobUserTimeLimit;
        public uint LimitFlags;
        public UIntPtr MinimumWorkingSetSize;
        public UIntPtr MaximumWorkingSetSize;
        public uint ActiveProcessLimit;
        public long Affinity;
        public uint PriorityClass;
        public uint SchedulingClass;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct IO_COUNTERS
    {
        public ulong ReadOperationCount;
        public ulong WriteOperationCount;
        public ulong OtherOperationCount;
        public ulong ReadTransferCount;
        public ulong WriteTransferCount;
        public ulong OtherTransferCount;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct JOBOBJECT_EXTENDED_LIMIT_INFORMATION
    {
        public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation;
        public IO_COUNTERS IoInfo;
        public UIntPtr ProcessMemoryLimit;
        public UIntPtr JobMemoryLimit;
        public UIntPtr PeakProcessMemoryUsed;
        public UIntPtr PeakJobMemoryUsed;
    }
}
