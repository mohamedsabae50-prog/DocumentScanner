using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Windows.Forms;
using Microsoft.Win32;

internal static class NahlaLauncher
{
    [STAThread]
    private static int Main(string[] args)
    {
        string executable = Assembly.GetExecutingAssembly().Location;
        string executableDirectory = Path.GetDirectoryName(executable);

        if (HasArgument(args, "--create-shortcut"))
        {
            CreateShortcut(executable, executableDirectory);
            return 0;
        }

        if (HasArgument(args, "--shortcut"))
        {
            string shortcutPath = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory),
                "\u0646\u0647\u0644\u0629 \u0633\u0643\u0627\u0646\u0631 - Nahla Scanner.lnk");
            if (!File.Exists(shortcutPath))
                CreateShortcut(executable, executableDirectory);
        }

        string htmlPath = FindHtml(executableDirectory);
        if (htmlPath == null)
        {
            Message("Nahla Desktop could not find index.html beside the launcher or in a NahlaDesktop subfolder.");
            return 1;
        }

        string fileUri = new Uri(htmlPath).AbsoluteUri;
        string browser = FindBrowser();
        if (browser != null)
        {
            ProcessStartInfo start = new ProcessStartInfo();
            start.FileName = browser;
            start.Arguments = "--app=\"" + fileUri + "\" --window-size=1360,860";
            start.UseShellExecute = false;
            start.CreateNoWindow = true;
            start.WindowStyle = ProcessWindowStyle.Normal;
            Process.Start(start);
        }
        else
        {
            ProcessStartInfo start = new ProcessStartInfo();
            start.FileName = fileUri;
            start.UseShellExecute = true;
            Process.Start(start);
        }
        return 0;
    }

    private static bool HasArgument(string[] args, string expected)
    {
        foreach (string arg in args)
            if (String.Equals(arg, expected, StringComparison.OrdinalIgnoreCase))
                return true;
        return false;
    }

    private static string FindHtml(string executableDirectory)
    {
        string[] candidates = new string[]
        {
            Path.Combine(executableDirectory, "NahlaDesktop", "index.html"),
            Path.Combine(executableDirectory, "index.html")
        };
        foreach (string candidate in candidates)
            if (File.Exists(candidate))
                return candidate;
        return null;
    }

    private static string FindBrowser()
    {
        string[] names = new string[] { "msedge.exe", "chrome.exe", "brave.exe" };
        string[] roots = new string[]
        {
            Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86),
            Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData)
        };

        foreach (string name in names)
        {
            foreach (string path in BrowserPaths(name, roots))
                if (!String.IsNullOrEmpty(path) && File.Exists(path))
                    return path;

            string pathEnvironment = Environment.GetEnvironmentVariable("PATH") ?? String.Empty;
            foreach (string directory in pathEnvironment.Split(Path.PathSeparator))
            {
                string candidate = Path.Combine(directory.Trim(), name);
                if (File.Exists(candidate))
                    return candidate;
            }

            string registered = ReadAppPath(name);
            if (!String.IsNullOrEmpty(registered) && File.Exists(registered))
                return registered;
        }
        return null;
    }

    private static string[] BrowserPaths(string name, string[] roots)
    {
        string edge = Path.Combine("Microsoft", "Edge", "Application", name);
        string chrome = Path.Combine("Google", "Chrome", "Application", name);
        string brave = Path.Combine("BraveSoftware", "Brave-Browser", "Application", name);
        string relative = name == "msedge.exe" ? edge :
            name == "chrome.exe" ? chrome : brave;
        string[] paths = new string[roots.Length];
        for (int i = 0; i < roots.Length; i++)
            paths[i] = String.IsNullOrEmpty(roots[i]) ? null : Path.Combine(roots[i], relative);
        return paths;
    }

    private static string ReadAppPath(string name)
    {
        string keyPath = @"SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\" + name;
        RegistryKey key = Registry.LocalMachine.OpenSubKey(keyPath);
        if (key == null)
            key = Registry.CurrentUser.OpenSubKey(keyPath);
        if (key == null)
            return null;
        using (key)
        {
            string value = key.GetValue(null) as string;
            return value == null ? null : Environment.ExpandEnvironmentVariables(value.Trim('"'));
        }
    }

    private static void CreateShortcut(string target, string workingDirectory)
    {
        string desktop = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
        string shortcutPath = Path.Combine(
            desktop,
            "\u0646\u0647\u0644\u0629 \u0633\u0643\u0627\u0646\u0631 - Nahla Scanner.lnk");
        string temporaryPath = Path.Combine(
            desktop,
            "Nahla-" + Guid.NewGuid().ToString("N") + ".lnk");
        string icon = Path.Combine(workingDirectory, "nahla.ico");
        if (!File.Exists(icon))
            icon = Path.Combine(workingDirectory, "NahlaDesktop", "nahla.ico");
        if (!File.Exists(icon))
            icon = target;
        Type shellType = Type.GetTypeFromProgID("WScript.Shell");
        if (shellType == null)
            throw new InvalidOperationException("Windows Script Host is unavailable; the desktop shortcut could not be created.");

        object shell = Activator.CreateInstance(shellType);
        object shortcut = null;
        try
        {
            shortcut = shellType.InvokeMember(
                "CreateShortcut",
                BindingFlags.InvokeMethod,
                null,
                shell,
                new object[] { temporaryPath });
            Type shortcutType = shortcut.GetType();
            SetProperty(shortcutType, shortcut, "TargetPath", target);
            SetProperty(shortcutType, shortcut, "WorkingDirectory", workingDirectory);
            SetProperty(shortcutType, shortcut, "IconLocation", icon + ",0");
            SetProperty(shortcutType, shortcut, "Description", "Nahla Document Scanner & PDF Editor");
            shortcutType.InvokeMember("Save", BindingFlags.InvokeMethod, null, shortcut, null);
        }
        finally
        {
            if (shortcut != null && Marshal.IsComObject(shortcut))
                Marshal.ReleaseComObject(shortcut);
            if (Marshal.IsComObject(shell))
                Marshal.ReleaseComObject(shell);
        }

        if (File.Exists(shortcutPath))
            File.Delete(shortcutPath);
        File.Move(temporaryPath, shortcutPath);
    }

    private static void SetProperty(Type type, object target, string name, object value)
    {
        type.InvokeMember(name, BindingFlags.SetProperty, null, target, new object[] { value });
    }

    private static void Message(string text)
    {
        MessageBox.Show(text, "Nahla Desktop", MessageBoxButtons.OK, MessageBoxIcon.Error);
    }
}
