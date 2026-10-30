using System;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
#if WINDOW || HYBRID
using System.Windows.Forms;
#endif

// The JVM runs inside this executable: no java.exe/javaw.exe child process.
internal static class SeaDBLauncher
{
    [STAThread]
    private static int Main(string[] args)
    {
#if HYBRID
        if (args.Length != 0) AttachConsole(unchecked((uint)-1));
#endif
        int result = 1;
        // JNI recommends a dedicated thread with enough stack for Java execution.
        Thread thread = new Thread(() => {
            try { result = Run(args); }
            catch (Exception error) { Fail(error.Message); }
        }, 4 * 1024 * 1024);
        thread.Start();
        thread.Join();
        return result;
    }

    private static int Run(string[] args)
    {
        string exe = Assembly.GetExecutingAssembly().Location;
        string app = Path.GetFileNameWithoutExtension(exe);
        string bin = Path.Combine(Path.GetDirectoryName(exe), "java", "bin");
        string jvmPath = Path.Combine(bin, "server", "jvm.dll");
        if (!File.Exists(jvmPath)) throw new IOException("Java runtime not found:\n" + jvmPath);
        // Keep the runtime's DLL directory available to JVM and AWT dependencies.
        if (!SetDllDirectory(bin)) throw new Win32Exception(Marshal.GetLastWin32Error());
        IntPtr library = LoadLibrary(jvmPath);
        if (library == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error(), "Could not load " + jvmPath);
        IntPtr entry = GetProcAddress(library, "JNI_CreateJavaVM");
        if (entry == IntPtr.Zero) throw new IOException("JNI_CreateJavaVM is missing from the runtime.");
        CreateVm create = (CreateVm)Marshal.GetDelegateForFunctionPointer(entry, typeof(CreateVm));

        string dir = Path.Combine(Path.GetTempPath(), "SeaDB");
        Directory.CreateDirectory(dir);
        foreach (string old in Directory.GetFiles(dir, app + "-*.jar")) TryDelete(old);
        string jar = Path.Combine(dir, app + "-" + Process.GetCurrentProcess().Id + ".jar");
        using (Stream resource = Assembly.GetExecutingAssembly().GetManifestResourceStream("AppJar"))
        {
            if (resource == null) throw new IOException("Application data is missing from the executable.");
            using (Stream output = File.Create(jar)) resource.CopyTo(output);
        }
        try
        {
            string mainClass = ReadMainClass(jar);
            string classPath = NativePath(jar);
            string option = "-Djava.class.path=" + classPath;
            IntPtr optionText = Marshal.StringToHGlobalAnsi(option);
            IntPtr options = Marshal.AllocHGlobal(IntPtr.Size * 2);
            IntPtr vm, env;
            try
            {
                Marshal.WriteIntPtr(options, optionText);
                Marshal.WriteIntPtr(options, IntPtr.Size, IntPtr.Zero);
                VmArgs init = new VmArgs { version = 0x00010008, count = 1, options = options };
                int status = create(out vm, out env, ref init);
                if (status != 0) throw new IOException("Could not initialize Java (JNI error " + status + ").");
            }
            finally { Marshal.FreeHGlobal(options); Marshal.FreeHGlobal(optionText); }
            try
            {
                FindClass find = Function<FindClass>(env, 6);
                IntPtr clazz = find(env, mainClass.Replace('.', '/'));
                Check(env, clazz);
                IntPtr method = Function<GetStaticMethod>(env, 113)(env, clazz, "main", "([Ljava/lang/String;)V");
                Check(env, method);
                IntPtr strings = find(env, "java/lang/String");
                Check(env, strings);
                IntPtr array = Function<NewArray>(env, 172)(env, args.Length, strings, IntPtr.Zero);
                Check(env, array);
                for (int i = 0; i < args.Length; i++)
                {
                    IntPtr value = Function<NewString>(env, 163)(env, args[i], args[i].Length);
                    Check(env, value);
                    Function<SetArray>(env, 174)(env, array, i, value);
                    Function<DeleteRef>(env, 23)(env, value);
                    Check(env, array);
                }
                // Each jvalue occupies eight bytes, including on 32-bit platforms.
                IntPtr values = Marshal.AllocHGlobal(8);
                try
                {
                    Marshal.WriteInt64(values, array.ToInt64());
                    Function<CallMain>(env, 143)(env, clazz, method, values);
                }
                finally { Marshal.FreeHGlobal(values); }
                if (Function<ExceptionCheck>(env, 228)(env) != 0)
                {
                    Function<EnvAction>(env, 16)(env);
                    Fail("The application terminated with a Java exception. See standard error for details.");
                    return 1;
                }
                return 0;
            }
            finally
            {
                // Waits for Swing/server non-daemon threads after main returns.
                Function<DestroyVm>(vm, 3)(vm);
                // Do not unload jvm.dll: native shutdown code may still refer to it.
            }
        }
        finally { TryDelete(jar); }
    }

    private static string ReadMainClass(string jar)
    {
        using (ZipArchive archive = ZipFile.OpenRead(jar))
        {
            ZipArchiveEntry manifest = archive.GetEntry("META-INF/MANIFEST.MF");
            if (manifest == null) throw new IOException("The JAR manifest is missing.");
            using (StreamReader reader = new StreamReader(manifest.Open(), Encoding.UTF8))
            {
                string text = reader.ReadToEnd().Replace("\r\n", "\n").Replace("\n ", "");
                foreach (string line in text.Split('\n'))
                {
                    if (line.Length == 0) break;
                    if (line.StartsWith("Main-Class: ", StringComparison.OrdinalIgnoreCase)) return line.Substring(12).Trim();
                }
            }
        }
        throw new IOException("The JAR manifest does not specify Main-Class.");
    }

    private static string NativePath(string path)
    {
        // JVM option strings use the Windows system code page, unlike JNI NewString.
        IntPtr encoded = Marshal.StringToHGlobalAnsi(path);
        try { if (Marshal.PtrToStringAnsi(encoded) == path) return path; }
        finally { Marshal.FreeHGlobal(encoded); }
        StringBuilder shortPath = new StringBuilder(32768);
        uint length = GetShortPathName(path, shortPath, (uint)shortPath.Capacity);
        if (length > 0 && length < shortPath.Capacity)
        {
            string candidate = shortPath.ToString();
            encoded = Marshal.StringToHGlobalAnsi(candidate);
            try { if (Marshal.PtrToStringAnsi(encoded) == candidate) return candidate; }
            finally { Marshal.FreeHGlobal(encoded); }
        }
        throw new IOException("The temporary folder cannot be represented in the Windows code page. Set TEMP to a compatible path.");
    }

    private static T Function<T>(IntPtr owner, int slot) where T : class
    {
        // Stable JNI function-table indices from jni.h / JNI specification.
        IntPtr address = Marshal.ReadIntPtr(Marshal.ReadIntPtr(owner), slot * IntPtr.Size);
        return (T)(object)Marshal.GetDelegateForFunctionPointer(address, typeof(T));
    }
    private static void Check(IntPtr env, IntPtr value)
    {
        if (Function<ExceptionCheck>(env, 228)(env) != 0)
        {
            Function<EnvAction>(env, 16)(env);
            throw new IOException("Java initialization failed. See standard error for details.");
        }
        if (value == IntPtr.Zero) throw new IOException("Java returned an empty JNI reference.");
    }
    private static void TryDelete(string path)
    {
        // System.exit/forced termination can leave the JAR until the next launch.
        try { File.Delete(path); }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }
    private static void Fail(string message)
    {
#if WINDOW
        MessageBox.Show(message, Path.GetFileNameWithoutExtension(Assembly.GetExecutingAssembly().Location), MessageBoxButtons.OK, MessageBoxIcon.Error);
#elif HYBRID
        if (Environment.GetCommandLineArgs().Length == 1) MessageBox.Show(message, "SeaDB", MessageBoxButtons.OK, MessageBoxIcon.Error);
        else Console.Error.WriteLine(message);
#else
        Console.Error.WriteLine(message);
#endif
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct VmArgs { public int version, count; public IntPtr options; public byte ignoreUnrecognized; }
    [UnmanagedFunctionPointer(CallingConvention.Winapi)] private delegate int CreateVm(out IntPtr vm, out IntPtr env, ref VmArgs args);
    [UnmanagedFunctionPointer(CallingConvention.Winapi)] private delegate int DestroyVm(IntPtr vm);
    [UnmanagedFunctionPointer(CallingConvention.Winapi, CharSet = CharSet.Ansi)] private delegate IntPtr FindClass(IntPtr env, string name);
    [UnmanagedFunctionPointer(CallingConvention.Winapi, CharSet = CharSet.Ansi)] private delegate IntPtr GetStaticMethod(IntPtr env, IntPtr clazz, string name, string signature);
    [UnmanagedFunctionPointer(CallingConvention.Winapi, CharSet = CharSet.Unicode)] private delegate IntPtr NewString(IntPtr env, string value, int length);
    [UnmanagedFunctionPointer(CallingConvention.Winapi)] private delegate IntPtr NewArray(IntPtr env, int length, IntPtr clazz, IntPtr initial);
    [UnmanagedFunctionPointer(CallingConvention.Winapi)] private delegate void SetArray(IntPtr env, IntPtr array, int index, IntPtr value);
    [UnmanagedFunctionPointer(CallingConvention.Winapi)] private delegate void CallMain(IntPtr env, IntPtr clazz, IntPtr method, IntPtr args);
    [UnmanagedFunctionPointer(CallingConvention.Winapi)] private delegate byte ExceptionCheck(IntPtr env);
    [UnmanagedFunctionPointer(CallingConvention.Winapi)] private delegate void EnvAction(IntPtr env);
    [UnmanagedFunctionPointer(CallingConvention.Winapi)] private delegate void DeleteRef(IntPtr env, IntPtr value);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern bool SetDllDirectory(string path);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern IntPtr LoadLibrary(string path);
    [DllImport("kernel32.dll", CharSet = CharSet.Ansi, ExactSpelling = true)] private static extern IntPtr GetProcAddress(IntPtr module, string name);
    [DllImport("kernel32.dll")] private static extern bool AttachConsole(uint processId);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] private static extern uint GetShortPathName(string path, StringBuilder output, uint length);
}
