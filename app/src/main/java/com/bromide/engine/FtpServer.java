package com.bromide.engine;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A small FTP server: just enough of RFC 959 (+ EPSV, SIZE, MDTM) for cameras
 * that upload photos over FTP. Pure Java so it can be tested off-device.
 * Any user name is accepted (Bromide uses it to pick a film); the password must match.
 * Upload only: files can't be downloaded, and active mode only connects back to the client.
 */
public final class FtpServer {

    public interface Listener {
        /** A file finished uploading. {@code user} is the login name the camera used. */
        void onFileReceived(File file, String user);

        /** A camera connected. {@code local} is the phone address it reached us on. */
        void onClientConnected(String remote, String local);

        void onLog(String message);
    }

    private final int port;
    private final File root;
    private final String password;
    private final Listener listener;
    private volatile ServerSocket server;
    private volatile boolean running;
    private final ExecutorService clients = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ftp-client");
        t.setDaemon(true);
        return t;
    });
    private final List<Socket> open = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger sessions = new AtomicInteger();

    public FtpServer(int port, File root, String password, Listener listener) {
        this.port = port;
        this.root = root;
        this.password = password;
        this.listener = listener;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("can't create " + root);
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(port));
        server = ss;
        running = true;
        Thread t = new Thread(this::acceptLoop, "ftp-accept");
        t.setDaemon(true);
        t.start();
        log("FTP server listening on port " + port);
    }

    public synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        synchronized (open) {
            for (Socket s : open) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
            open.clear();
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                if (!isLocal(s.getInetAddress())) {
                    // Cameras reach the phone over its hotspot or a local Wi-Fi, never from the internet.
                    log("refused " + s.getInetAddress().getHostAddress() + " (not a local network address)");
                    s.close();
                    continue;
                }
                open.add(s);
                clients.execute(() -> {
                    try {
                        new Session(s).run();
                    } finally {
                        open.remove(s);
                        try {
                            s.close();
                        } catch (IOException ignored) {
                        }
                    }
                });
            } catch (IOException e) {
                if (running) log("accept failed: " + e.getMessage());
            }
        }
    }

    /** Loopback, private (10/8, 172.16/12, 192.168/16), link-local, or an IPv6 unique local address. */
    static boolean isLocal(InetAddress a) {
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xfe) == 0xfc;
    }

    private void log(String m) {
        if (listener != null) listener.onLog(m);
    }

    // ------------------------------------------------------------------------------

    private final class Session {
        final Socket ctrl;
        final int id = sessions.incrementAndGet();
        PrintWriter out;
        String user = null;
        boolean authed = false;
        int failedLogins = 0;
        String cwd = "/";
        ServerSocket pasv = null;
        InetSocketAddress active = null;
        String renameFrom = null;
        long restart = 0;

        Session(Socket s) {
            ctrl = s;
        }

        void run() {
            try {
                ctrl.setSoTimeout(10 * 60 * 1000);
                ctrl.setTcpNoDelay(true);
                BufferedReader in = new BufferedReader(
                    new InputStreamReader(ctrl.getInputStream(), StandardCharsets.UTF_8));
                out = new PrintWriter(new OutputStreamWriter(ctrl.getOutputStream(), StandardCharsets.UTF_8), true);
                if (listener != null) {
                    listener.onClientConnected(ctrl.getInetAddress().getHostAddress(),
                                               ctrl.getLocalAddress().getHostAddress());
                }
                reply(220, "Bromide ready");
                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    int sp = line.indexOf(' ');
                    String cmd = (sp < 0 ? line : line.substring(0, sp)).toUpperCase(Locale.ROOT);
                    String arg = sp < 0 ? "" : line.substring(sp + 1).trim();
                    if (!handle(cmd, arg)) break;
                }
            } catch (SocketException ignored) {
            } catch (IOException e) {
                log("session " + id + ": " + e.getMessage());
            } finally {
                closePasv();
            }
        }

        void reply(int code, String msg) {
            out.print(code + " " + msg + "\r\n");
            out.flush();
        }

        boolean handle(String cmd, String arg) throws IOException {
            switch (cmd) {
                case "USER":
                    user = arg;
                    authed = false;
                    reply(331, "Password required");
                    return true;
                case "PASS":
                    if (user == null) {
                        reply(503, "Login with USER first");
                    } else if (password == null || password.isEmpty() || password.equals(arg)) {
                        authed = true;
                        reply(230, "Logged in");
                        log("camera logged in as '" + user + "'");
                    } else {
                        log("wrong password from " + ctrl.getInetAddress().getHostAddress());
                        // Slow down guessing, and hang up after a few tries.
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        reply(530, "Login incorrect");
                        if (++failedLogins >= 3) return false;
                    }
                    return true;
                case "QUIT":
                    reply(221, "Bye");
                    return false;
                case "NOOP":
                    reply(200, "OK");
                    return true;
                case "SYST":
                    reply(215, "UNIX Type: L8");
                    return true;
                case "FEAT":
                    out.print("211-Features:\r\n UTF8\r\n PASV\r\n EPSV\r\n SIZE\r\n MDTM\r\n REST STREAM\r\n211 End\r\n");
                    out.flush();
                    return true;
                case "OPTS":
                    reply(200, "OK");
                    return true;
                case "HELP":
                    reply(214, "Commands OK");
                    return true;
                default:
                    break;
            }
            if (!authed) {
                reply(530, "Not logged in");
                return true;
            }
            switch (cmd) {
                case "PWD":
                case "XPWD":
                    reply(257, "\"" + cwd + "\" is the current directory");
                    break;
                case "CWD":
                case "XCWD": {
                    String p = resolve(arg);
                    if (file(p).isDirectory()) {
                        cwd = p;
                        reply(250, "OK");
                    } else {
                        reply(550, "No such directory");
                    }
                    break;
                }
                case "CDUP":
                case "XCUP":
                    cwd = resolve("..");
                    reply(250, "OK");
                    break;
                case "MKD":
                case "XMKD": {
                    String p = resolve(arg);
                    File f = file(p);
                    if (f.isDirectory()) reply(550, "Already exists");
                    else if (f.mkdirs()) reply(257, "\"" + p + "\" created");
                    else reply(550, "Can't create directory");
                    break;
                }
                case "RMD":
                case "XRMD": {
                    File f = file(resolve(arg));
                    reply(f.isDirectory() && f.delete() ? 250 : 550, "RMD");
                    break;
                }
                case "TYPE":
                case "MODE":
                case "STRU":
                    reply(200, "OK");
                    break;
                case "ALLO":
                    reply(202, "Not needed");
                    break;
                case "REST":
                    try {
                        restart = Long.parseLong(arg);
                        reply(350, "Restarting at " + restart);
                    } catch (NumberFormatException e) {
                        reply(501, "Bad offset");
                    }
                    break;
                case "PASV":
                    pasv(false);
                    break;
                case "EPSV":
                    pasv(true);
                    break;
                case "PORT":
                    port(arg);
                    break;
                case "STOR":
                    store(arg, restart > 0);
                    break;
                case "APPE":
                    store(arg, true);
                    break;
                case "RETR":
                    // Upload only: nobody else on the network gets to read what the camera sent.
                    reply(550, "Downloads are not available");
                    break;
                case "SIZE": {
                    File f = file(resolve(arg));
                    if (f.isFile()) reply(213, String.valueOf(f.length()));
                    else reply(550, "No such file");
                    break;
                }
                case "MDTM": {
                    File f = file(resolve(arg));
                    if (f.exists()) {
                        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
                        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
                        reply(213, fmt.format(new Date(f.lastModified())));
                    } else {
                        reply(550, "No such file");
                    }
                    break;
                }
                case "LIST":
                case "NLST":
                    list(arg, cmd.equals("NLST"));
                    break;
                case "DELE": {
                    File f = file(resolve(arg));
                    reply(f.isFile() && f.delete() ? 250 : 550, "DELE");
                    break;
                }
                case "RNFR": {
                    String p = resolve(arg);
                    if (file(p).exists()) {
                        renameFrom = p;
                        reply(350, "Ready for RNTO");
                    } else {
                        reply(550, "No such file");
                    }
                    break;
                }
                case "RNTO": {
                    if (renameFrom == null) {
                        reply(503, "RNFR first");
                        break;
                    }
                    File from = file(renameFrom), to = file(resolve(arg));
                    renameFrom = null;
                    if (from.renameTo(to)) {
                        reply(250, "Renamed");
                        if (to.isFile() && listener != null) listener.onFileReceived(to, user);
                    } else {
                        reply(550, "Rename failed");
                    }
                    break;
                }
                case "STAT":
                    reply(211, "Bromide FTP");
                    break;
                default:
                    reply(502, "Command not implemented");
            }
            return true;
        }

        // ---- paths ----

        String resolve(String arg) {
            String p = arg == null ? "" : arg.replace('\\', '/');
            if (p.startsWith("-")) p = "";  // "LIST -la"
            String base = p.startsWith("/") ? "" : cwd;
            List<String> parts = new ArrayList<>();
            for (String s : (base + "/" + p).split("/")) {
                if (s.isEmpty() || s.equals(".")) continue;
                if (s.equals("..")) {
                    if (!parts.isEmpty()) parts.remove(parts.size() - 1);
                } else {
                    parts.add(s);
                }
            }
            return "/" + String.join("/", parts);
        }

        File file(String virtualPath) {
            return virtualPath.equals("/") ? root : new File(root, virtualPath.substring(1));
        }

        // ---- data connections ----

        void pasv(boolean extended) throws IOException {
            closePasv();
            active = null;
            InetAddress local = ctrl.getLocalAddress();
            pasv = new ServerSocket();
            pasv.bind(new InetSocketAddress(local, 0));
            pasv.setSoTimeout(30000);
            int p = pasv.getLocalPort();
            if (extended) {
                reply(229, "Entering Extended Passive Mode (|||" + p + "|)");
            } else {
                String host = local instanceof Inet4Address ? local.getHostAddress() : "127.0.0.1";
                if (host.startsWith("::ffff:")) host = host.substring(7);
                reply(227, "Entering Passive Mode (" + host.replace('.', ',') + "," + (p >> 8) + "," + (p & 0xff) + ")");
            }
        }

        void port(String arg) {
            String[] n = arg.split(",");
            if (n.length != 6) {
                reply(501, "Bad PORT");
                return;
            }
            try {
                String host = n[0].trim() + "." + n[1].trim() + "." + n[2].trim() + "." + n[3].trim();
                int p = Integer.parseInt(n[4].trim()) * 256 + Integer.parseInt(n[5].trim());
                // Active mode may only connect back to the camera itself (no FTP bounce to other hosts).
                String client = ctrl.getInetAddress().getHostAddress();
                if (client.startsWith("::ffff:")) client = client.substring(7);
                if (!host.equals(client)) {
                    reply(504, "PORT must point to your own address");
                    return;
                }
                closePasv();
                active = new InetSocketAddress(host, p);
                reply(200, "PORT OK");
            } catch (NumberFormatException e) {
                reply(501, "Bad PORT");
            }
        }

        Socket openData() throws IOException {
            if (pasv != null) {
                try {
                    return pasv.accept();
                } finally {
                    closePasv();
                }
            }
            if (active != null) {
                Socket s = new Socket();
                s.connect(active, 15000);
                active = null;
                return s;
            }
            throw new IOException("no data connection (use PASV)");
        }

        void closePasv() {
            if (pasv != null) {
                try {
                    pasv.close();
                } catch (IOException ignored) {
                }
                pasv = null;
            }
        }

        // ---- transfers ----

        void store(String arg, boolean append) {
            String p = resolve(arg);
            File f = file(p);
            File parent = f.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            long offset = restart;
            restart = 0;
            reply(150, "Ready to receive " + p);
            long total = 0;
            boolean ok = false;
            try (Socket data = openData()) {
                data.setSoTimeout(60000);
                boolean appendMode = append || offset > 0;
                if (offset > 0 && f.length() > offset) truncate(f, offset);
                try (InputStream in = data.getInputStream();
                     OutputStream o = new BufferedOutputStream(new FileOutputStream(f, appendMode), 1 << 16)) {
                    byte[] buf = new byte[1 << 16];
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        o.write(buf, 0, r);
                        total += r;
                    }
                }
                ok = true;
            } catch (IOException e) {
                reply(426, "Transfer failed: " + e.getMessage());
                log("upload of " + f.getName() + " failed: " + e.getMessage());
            }
            if (ok) {
                reply(226, "Transfer complete");
                log("received " + f.getName() + " (" + total / 1024 + " KB)");
                if (listener != null) listener.onFileReceived(f, user);
            }
        }

        void truncate(File f, long len) throws IOException {
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw")) {
                raf.setLength(len);
            }
        }

        void list(String arg, boolean namesOnly) {
            File dir = file(resolve(arg));
            reply(150, "Listing");
            try (Socket data = openData();
                 PrintWriter w = new PrintWriter(new OutputStreamWriter(data.getOutputStream(), StandardCharsets.UTF_8))) {
                File[] files = dir.isDirectory() ? dir.listFiles() : (dir.isFile() ? new File[]{dir} : new File[0]);
                SimpleDateFormat fmt = new SimpleDateFormat("MMM dd HH:mm", Locale.US);
                if (files != null) {
                    for (File f : files) {
                        if (namesOnly) {
                            w.print(f.getName() + "\r\n");
                        } else {
                            w.print((f.isDirectory() ? "drwxr-xr-x" : "-rw-r--r--") + " 1 bromide bromide "
                                    + f.length() + " " + fmt.format(new Date(f.lastModified())) + " " + f.getName() + "\r\n");
                        }
                    }
                }
                w.flush();
            } catch (IOException e) {
                reply(426, "Listing failed");
                return;
            }
            reply(226, "Done");
        }
    }
}
