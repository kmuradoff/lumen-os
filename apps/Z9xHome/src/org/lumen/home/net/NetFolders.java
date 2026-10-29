package org.lumen.home.net;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.Toast;

import org.lumen.home.R;
import org.lumen.home.ui.ContextPanel;
import org.lumen.home.ui.ListPanel;
import org.lumen.home.ui.Theme;
import org.lumen.home.usb.PhotoActivity;
import org.lumen.home.usb.PlayerActivity;
import org.lumen.home.usb.UsbFiles;
import org.lumen.home.usb.UsbFilesActivity;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Files > Network folders (Lumen OS 1.0.6; owner: connect by IP, port, login and password and watch the files
 * remotely): the SMB 2/3 shares of a Windows PC, a Mac, Samba or a NAS in the Files side panel. The first page of
 * Files gets one row; it opens the saved servers, "Add a server" and the SMB servers that announce themselves on
 * the LAN (mDNS _smb._tcp, looked for while that page is shown, at most 10 s). A server's form (address, port,
 * login, password, share, name; OK on a row edits it on its own page) connects off the main thread; success
 * saves the server (NetServers: the password encrypted by the keystore) and shows its shares, or the folder
 * of the share given; failure stays on the form with the reason. A long OK on a saved server: open, edit,
 * delete. Folders open like USB folders (BACK goes up); files open like USB files: video and music in the
 * user's app for the type, else the built-in player, photos in the photo viewer (LEFT / RIGHT among the
 * folder's photos), anything else in its app, all through NetFilesProvider's content:// URIs.
 *
 * UsbFilesActivity owns the panel and its BACK bookkeeping; this class keeps its own stack of the pages it
 * pushed ({@link #depth()}) and is told about every BACK first ({@link #onBack()}), so the activity's folder
 * stack and menu depth stay untouched while network pages are shown. Browsing uses one SMB connection (on a
 * single worker thread, reconnected once when the server dropped it); every playing file has its own.
 */
public final class NetFolders {
    private static final String TAG = "Z9xHomeNet";
    private static final int MAX_ENTRIES = 400;
    private static final long DISCOVERY_MS = 10_000;
    private static final int K_NET = 0, K_FORM = 1, K_EDITOR = 2, K_SHARES = 3, K_FOLDER = 4, K_MENU = 5;
    private static final int F_HOST = 0, F_PORT = 1, F_LOGIN = 2, F_PASSWORD = 3, F_SHARE = 4, F_NAME = 5;

    /** A page this class pushed on the panel; async results check that theirs is still on top. */
    private static final class Page {
        final int kind;
        NetServers.Server server;
        String share = "", path = "";
        boolean isNew, busy;
        ListPanel.Item fHost, fPort, fLogin, fPassword, fShare, fName, status;
        ArrayList<ListPanel.Item> items;

        Page(int kind) {
            this.kind = kind;
        }
    }

    /** The tag of a network folder's row. */
    static final class NetEntry {
        String name;
        int type;
        long size;
    }

    /** An SMB server announced on the LAN. */
    private static final class Found {
        String name, host;
        int port;
    }

    private interface Op<T> {
        T run(Smb2 c) throws IOException;
    }

    private final Activity mA;
    private final ContextPanel mPanel;
    private final Runnable mOnLeft;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mIo = Executors.newSingleThreadExecutor();
    private final ArrayList<Page> mPages = new ArrayList<>();
    // the browsing connection: worker thread only
    private Smb2 mConn;
    private NetServers.Server mConnServer;
    private final Map<String, Integer> mTrees = new HashMap<>();
    // discovery: main thread only
    private NsdManager mNsd;
    private NsdManager.DiscoveryListener mDisc;
    private final ArrayList<Found> mFound = new ArrayList<>();
    private final ArrayList<NsdServiceInfo> mToResolve = new ArrayList<>();
    private boolean mResolving;
    private final Runnable mStopDiscovery = this::stopDiscovery;

    /** {@code onLeft}: run when the last network page is gone (the places page is shown again). */
    public NetFolders(Activity a, ContextPanel panel, Runnable onLeft) {
        mA = a;
        mPanel = panel;
        mOnLeft = onLeft;
        Smb2.sLog = s -> Log.i(TAG, s);
    }

    private String str(int id) {
        return mA.getString(id);
    }

    /** The row of the places page. */
    public ListPanel.Item entryItem() {
        return ListPanel.Item.action(R.drawable.ic_server, str(R.string.net_title), this::showNet).value(str(R.string.net_sub));
    }

    /** Network pages on the panel (0 = none). */
    public int depth() {
        return mPages.size();
    }

    /** BACK up on the panel, before it pops its page: true when that page was ours. */
    public boolean onBack() {
        if (mPages.isEmpty()) return false;
        Page gone = mPages.remove(mPages.size() - 1);
        if (gone.kind == K_NET) stopDiscovery();
        // the panel pops after this returns: look at the new top page then
        mMain.post(this::onTopShown);
        if (mPages.isEmpty()) mMain.post(mOnLeft);
        return true;
    }

    /** A long OK menu for this row's tag (a saved server)? */
    public boolean hasMenu(Object tag) {
        return tag instanceof NetServers.Server && !mPages.isEmpty();
    }

    public void showMenu(Object tag) {
        if (!(tag instanceof NetServers.Server)) return;
        NetServers.Server s = (NetServers.Server) tag;
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        l.add(ListPanel.Item.action(R.drawable.ic_open, str(R.string.files_menu_open), () -> {
            popTop();
            openServer(s);
        }));
        l.add(ListPanel.Item.action(R.drawable.ic_edit, str(R.string.net_edit), () -> {
            popTop();
            showForm(s.copy(), false, null);
        }));
        l.add(ListPanel.Item.action(R.drawable.ic_trash, str(R.string.files_delete), () -> confirmDelete(s)));
        push(new Page(K_MENU), s.label(), hostPort(s), l);
    }

    public void onStop() {
        stopDiscovery();
    }

    public void onDestroy() {
        stopDiscovery();
        mMain.removeCallbacksAndMessages(null);
        mIo.execute(this::dropConn);
        mIo.shutdown();
    }

    // ------------------------------------------------------------------ page stack

    private Page top() {
        return mPages.isEmpty() ? null : mPages.get(mPages.size() - 1);
    }

    private void push(Page p, CharSequence title, CharSequence sub, List<ListPanel.Item> items) {
        pushEditor(p, title, sub, null, items);
    }

    private void pushEditor(Page p, CharSequence title, CharSequence sub, EditText e, List<ListPanel.Item> items) {
        Page prev = top();
        if (prev != null && prev.kind == K_NET) stopDiscovery();
        mPages.add(p);
        mPanel.pushEditor(title, sub, e, items);
    }

    /** Drops our top page from the panel (not BACK: the panel is told here). */
    private void popTop() {
        if (mPages.isEmpty()) return;
        Page gone = mPages.remove(mPages.size() - 1);
        if (gone.kind == K_NET) stopDiscovery();
        mPanel.pop();
        onTopShown();
        if (mPages.isEmpty()) mOnLeft.run();
    }

    /** Our page that is on top again: the servers page shows the saved list and looks for servers again. */
    private void onTopShown() {
        Page t = top();
        if (t == null || t.kind != K_NET || mPanel.depth() != mPages.size() + 1) return;
        mPanel.replace(netItems());
        startDiscovery();
    }

    private static ArrayList<ListPanel.Item> loading() {
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        l.add(ListPanel.Item.info(0, "…"));
        return l;
    }

    private static String hostPort(NetServers.Server s) {
        return s.port == Smb2.DEFAULT_PORT ? s.host : s.host + ":" + s.port;
    }

    // ------------------------------------------------------------------ servers page

    private void showNet() {
        mFound.clear();
        List<NetServers.Server> saved = NetServers.all(mA);
        push(new Page(K_NET), str(R.string.net_title), str(saved.isEmpty() ? R.string.net_sub : R.string.net_hint), netItems());
        startDiscovery();
    }

    private ArrayList<ListPanel.Item> netItems() {
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        List<NetServers.Server> saved = NetServers.all(mA);
        for (NetServers.Server s : saved) {
            ListPanel.Item it = ListPanel.Item.action(R.drawable.ic_server, s.label(), () -> openServer(s))
                    .value(s.share.isEmpty() ? hostPort(s) : hostPort(s) + " / " + s.share);
            it.tag = s;
            l.add(it);
        }
        l.add(ListPanel.Item.action(R.drawable.ic_plus, str(R.string.net_add), () -> showForm(NetServers.create(), true, null)));
        boolean header = false;
        for (Found f : mFound) {
            boolean known = false;
            for (NetServers.Server s : saved) known |= s.host.equalsIgnoreCase(f.host);
            if (known) continue;
            if (!header) {
                l.add(ListPanel.Item.info(0, str(R.string.net_found)).kind(ListPanel.HEADER));
                header = true;
            }
            l.add(ListPanel.Item.action(R.drawable.ic_server, f.name, () -> {
                NetServers.Server s = NetServers.create();
                s.host = f.host;
                s.port = f.port > 0 ? f.port : Smb2.DEFAULT_PORT;
                s.name = f.name;
                showForm(s, true, null);
            }).value(f.host));
        }
        return l;
    }

    private void confirmDelete(NetServers.Server s) {
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        l.add(ListPanel.Item.action(R.drawable.ic_trash, str(R.string.files_delete), () -> {
            NetServers.delete(mA, s.id);
            Log.i(TAG, "deleted server " + s.id);
            mIo.execute(() -> {
                if (mConnServer != null && mConnServer.id.equals(s.id)) dropConn();
            });
            popTop();                                  // the confirmation
            popTop();                                  // the menu: the servers page, refreshed
        }));
        l.add(ListPanel.Item.action(R.drawable.ic_close, str(R.string.files_cancel), () -> {
            popTop();
            popTop();
        }));
        push(new Page(K_MENU), mA.getString(R.string.files_delete_confirm, s.label()), hostPort(s), l);
    }

    // ------------------------------------------------------------------ the server form

    private void showForm(NetServers.Server s, boolean isNew, String note) {
        Page p = new Page(K_FORM);
        p.server = s;
        p.isNew = isNew;
        p.fHost = ListPanel.Item.action(0, str(R.string.net_address), () -> editField(p, F_HOST));
        p.fPort = ListPanel.Item.action(0, str(R.string.net_port), () -> editField(p, F_PORT));
        p.fLogin = ListPanel.Item.action(0, str(R.string.net_login), () -> editField(p, F_LOGIN));
        p.fPassword = ListPanel.Item.action(0, str(R.string.net_password), () -> editField(p, F_PASSWORD));
        p.fShare = ListPanel.Item.action(0, str(R.string.net_share), () -> editField(p, F_SHARE));
        p.fName = ListPanel.Item.action(0, str(R.string.net_name), () -> editField(p, F_NAME));
        p.items = new ArrayList<>();
        p.items.add(p.fHost);
        p.items.add(p.fPort);
        p.items.add(p.fLogin);
        p.items.add(p.fPassword);
        p.items.add(p.fShare);
        p.items.add(p.fName);
        p.items.add(ListPanel.Item.action(R.drawable.ic_check, str(R.string.net_connect), () -> connect(p)));
        if (note != null) {
            p.status = ListPanel.Item.info(R.drawable.ic_info, note);
            p.items.add(p.status);
        }
        formValues(p);
        push(p, isNew ? str(R.string.net_add) : s.label(), isNew ? str(R.string.net_sub) : hostPort(s), p.items);
    }

    private void formValues(Page p) {
        NetServers.Server s = p.server;
        String none = str(R.string.net_not_set);
        p.fHost.value(s.host.isEmpty() ? none : s.host);
        p.fPort.value(Integer.toString(s.port));
        p.fLogin.value(s.login.isEmpty() ? str(R.string.net_guest) : s.login);
        p.fPassword.value(s.password == null || s.password.isEmpty() ? none
                : "••••••••••".substring(0, Math.min(10, Math.max(4, s.password.length()))));
        p.fShare.value(s.share.isEmpty() ? str(R.string.net_all_shares) : s.share);
        p.fName.value(s.name.isEmpty() ? (s.host.isEmpty() ? none : s.host) : s.name);
    }

    private void setStatus(Page p, String text) {
        if (p.status == null) {
            p.status = ListPanel.Item.info(R.drawable.ic_info, text);
            p.items.add(p.status);
        } else {
            p.status.text = text;
        }
        if (top() == p) mPanel.replace(p.items);
    }

    private void editField(Page form, int field) {
        NetServers.Server s = form.server;
        String text, hint;
        int title, type;
        switch (field) {
            case F_HOST:
                title = R.string.net_address;
                text = s.host;
                hint = str(R.string.net_address_hint);
                type = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI;
                break;
            case F_PORT:
                title = R.string.net_port;
                text = Integer.toString(s.port);
                hint = Integer.toString(Smb2.DEFAULT_PORT);
                type = InputType.TYPE_CLASS_NUMBER;
                break;
            case F_LOGIN:
                title = R.string.net_login;
                text = s.login;
                hint = str(R.string.net_login_hint);
                type = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
                break;
            case F_PASSWORD:
                title = R.string.net_password;
                text = s.password == null ? "" : s.password;
                hint = "";
                type = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
                break;
            case F_SHARE:
                title = R.string.net_share;
                text = s.share;
                hint = str(R.string.net_all_shares);
                type = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
                break;
            default:
                title = R.string.net_name;
                text = s.name;
                hint = s.host;
                type = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS;
                break;
        }
        EditText e = editor(hint, text, type);
        Page ep = new Page(K_EDITOR);
        Runnable save = () -> {
            if (top() != ep) return;                   // Done and Enter both fire, once is enough
            String v = e.getText().toString();
            switch (field) {
                case F_HOST: s.host = v.trim().replace("\\", "").replace("/", ""); break;
                case F_PORT: s.port = port(v); break;
                case F_LOGIN: s.login = v.trim(); break;
                case F_PASSWORD: s.password = v; break;
                case F_SHARE: s.share = cleanShare(v); break;
                default: s.name = v.trim(); break;
            }
            formValues(form);
            hideIme(e);
            popTop();                                  // the form again, its rows updated
        };
        e.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_DONE || ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && ev.getAction() == KeyEvent.ACTION_DOWN) save.run();
            return true;
        });
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        l.add(ListPanel.Item.action(R.drawable.ic_check, str(R.string.save), save));
        pushEditor(ep, str(title), form.isNew ? str(R.string.net_add) : s.label(), e, l);
        e.postDelayed(() -> {
            if (top() != ep) return;
            InputMethodManager imm = mA.getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(e, 0);
        }, 150);
    }

    private void hideIme(EditText e) {
        InputMethodManager imm = mA.getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(e.getWindowToken(), 0);
    }

    static int port(String v) {
        try {
            int p = Integer.parseInt(v.trim());
            return p > 0 && p < 65536 ? p : Smb2.DEFAULT_PORT;
        } catch (NumberFormatException e) {
            return Smb2.DEFAULT_PORT;
        }
    }

    static String cleanShare(String v) {
        String s = v.trim().replace('\\', '/');
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        int slash = s.indexOf('/');
        return slash >= 0 ? s.substring(0, slash) : s;   // a share, not a path inside it
    }

    /** A one-line field like Home's rename editor. */
    private EditText editor(CharSequence hint, CharSequence text, int inputType) {
        EditText e = new EditText(mA);
        Theme.text(e, 30, Theme.REGULAR, Theme.TEXT1);
        e.setHint(hint);
        e.setHintTextColor(Theme.TEXT3);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setText(text);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        e.setGravity(Gravity.CENTER_VERTICAL);
        e.setPadding(Theme.px(28), 0, Theme.px(28), 0);
        e.setTextCursorDrawable(R.drawable.cursor);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.SURFACE2);
        bg.setCornerRadius(Theme.pxf(38));
        e.setBackground(bg);
        if (text != null) e.setSelection(text.length());
        return e;
    }

    /** Connect on the form: success saves the server and shows its shares (or its share's folder). */
    private void connect(Page form) {
        NetServers.Server s = form.server;
        if (s.host.isEmpty()) {
            setStatus(form, str(R.string.net_err_address));
            return;
        }
        if (form.busy) return;
        form.busy = true;
        setStatus(form, str(R.string.net_connecting));
        final NetServers.Server snap = s.copy();
        if (snap.password == null) snap.password = "";
        mIo.execute(() -> {
            String err = null;
            List<String> shares = null;
            List<Smb2.Entry> entries = null;
            Smb2 c = null;
            try {
                c = Smb2.connect(snap.host, snap.port, snap.login, snap.password);
                int tree = -1;
                if (!snap.share.isEmpty()) {
                    tree = c.treeConnect(snap.share);
                    entries = c.list(tree, "");
                } else {
                    try {
                        shares = c.listShares();
                    } catch (IOException e) {
                        Log.i(TAG, "share list of " + snap.host + ": " + e);
                    }
                }
                dropConn();
                mConn = c;
                mConnServer = snap.copy();
                if (tree >= 0) mTrees.put(snap.share, tree);
                Log.i(TAG, "connected to " + snap.host + " (SMB " + c.dialect() + (c.guest() ? ", guest" : "") + ")");
            } catch (IOException | RuntimeException e) {
                Log.i(TAG, "connect " + snap.host + ":" + snap.port + ": " + e);
                if (c != null) c.close();
                err = message(e);
            }
            final String fErr = err;
            final List<String> fShares = shares;
            final List<Smb2.Entry> fEntries = entries;
            mMain.post(() -> {
                form.busy = false;
                if (top() != form) return;             // the user went back: nothing saved
                if (fErr != null) {
                    setStatus(form, fErr);
                    return;
                }
                NetServers.save(mA, snap);
                popTop();
                // a failed server page under the form (opened from the list) goes too
                Page t = top();
                if (t != null && t.kind == K_SHARES && t.server.id.equals(snap.id)) popTop();
                if (!snap.share.isEmpty()) openFolder(snap, snap.share, "", fEntries);
                else showShares(snap, fShares);
            });
        });
    }

    String message(Throwable e) {
        if (e instanceof SmbException) {
            switch (((SmbException) e).kind) {
                case SmbException.LOGON: return str(R.string.net_err_logon);
                case SmbException.DENIED: return str(R.string.net_err_denied);
                case SmbException.NO_SHARE: return str(R.string.net_err_share);
                case SmbException.UNREACHABLE: return str(R.string.net_err_unreachable);
                case SmbException.ENCRYPTION: return str(R.string.net_err_encrypt);
                default:
            }
        }
        String m = e.getMessage();
        return mA.getString(R.string.net_err_other, m == null ? e.getClass().getSimpleName() : m);
    }

    // ------------------------------------------------------------------ shares

    /** OK on a saved server: its shares (or the folder of its share); the form when its password is unknown. */
    private void openServer(NetServers.Server s) {
        if (s.password == null) {
            showForm(s.copy(), false, str(R.string.net_need_password));
            return;
        }
        if (!s.share.isEmpty()) {
            openFolder(s, s.share, "", null);
            return;
        }
        Page p = new Page(K_SHARES);
        p.server = s;
        push(p, s.label(), hostPort(s), loading());
        loadShares(p);
    }

    private void loadShares(Page p) {
        final NetServers.Server s = p.server;
        mIo.execute(() -> {
            String err = null;
            List<String> shares = null;
            try {
                withConn(s, c -> c);
            } catch (IOException | RuntimeException e) {
                Log.i(TAG, "connect " + s.host + ": " + e);
                err = message(e);
            }
            if (err == null) {
                try {
                    shares = withConn(s, Smb2::listShares);
                } catch (IOException | RuntimeException e) {
                    Log.i(TAG, "share list of " + s.host + ": " + e);
                }
            }
            final String fErr = err;
            final List<String> fShares = shares;
            mMain.post(() -> {
                if (top() != p) return;
                mPanel.replace(fErr != null ? errorItems(fErr, () -> {
                    mPanel.replace(loading());
                    loadShares(p);
                }, s) : shareItems(s, fShares));
            });
        });
    }

    /** The shares page for a server just connected from its form. */
    private void showShares(NetServers.Server s, List<String> shares) {
        Page p = new Page(K_SHARES);
        p.server = s;
        push(p, s.label(), hostPort(s), shareItems(s, shares));
    }

    private ArrayList<ListPanel.Item> shareItems(NetServers.Server s, List<String> shares) {
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        if (shares == null || shares.isEmpty()) {
            l.add(ListPanel.Item.info(R.drawable.ic_info, str(R.string.net_no_shares)));
        } else {
            for (String name : shares) {
                l.add(ListPanel.Item.action(R.drawable.ic_folder, name, () -> openFolder(s, name, "", null)));
            }
        }
        l.add(ListPanel.Item.action(R.drawable.ic_edit, str(R.string.net_enter_share), () -> enterShare(s)));
        return l;
    }

    private ArrayList<ListPanel.Item> errorItems(String err, Runnable retry, NetServers.Server s) {
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        l.add(ListPanel.Item.info(R.drawable.ic_info, err));
        l.add(ListPanel.Item.action(R.drawable.ic_update, str(R.string.net_retry), retry));
        if (s != null) {
            l.add(ListPanel.Item.action(R.drawable.ic_edit, str(R.string.net_edit), () -> showForm(s.copy(), false, null)));
        }
        return l;
    }

    private void enterShare(NetServers.Server s) {
        EditText e = editor(str(R.string.net_share), "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        Page ep = new Page(K_EDITOR);
        Runnable go = () -> {
            if (top() != ep) return;
            String name = cleanShare(e.getText().toString());
            if (name.isEmpty()) return;
            hideIme(e);
            popTop();
            openFolder(s, name, "", null);
        };
        e.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_DONE || ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && ev.getAction() == KeyEvent.ACTION_DOWN) go.run();
            return true;
        });
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        l.add(ListPanel.Item.action(R.drawable.ic_folder, str(R.string.files_menu_open), go));
        pushEditor(ep, str(R.string.net_enter_share), s.label(), e, l);
        e.postDelayed(() -> {
            if (top() != ep) return;
            InputMethodManager imm = mA.getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(e, 0);
        }, 150);
    }

    // ------------------------------------------------------------------ folders

    private static String join(String path, String name) {
        return path.isEmpty() ? name : path + "/" + name;
    }

    /** Pushes a folder page at once ("…" while it is read); {@code preloaded} skips the read. */
    private void openFolder(NetServers.Server s, String share, String path, List<Smb2.Entry> preloaded) {
        Page p = new Page(K_FOLDER);
        p.server = s;
        p.share = share;
        p.path = path;
        String title = path.isEmpty() ? share : path.substring(path.lastIndexOf('/') + 1);
        String sub = s.label() + " / " + share + (path.isEmpty() ? "" : " / " + path.replace("/", " / "));
        if (preloaded != null) {
            push(p, title, sub, folderItems(p, preloaded));
            return;
        }
        push(p, title, sub, loading());
        loadFolder(p);
    }

    private void loadFolder(Page p) {
        final NetServers.Server s = p.server;
        final String share = p.share, path = p.path;
        mIo.execute(() -> {
            List<Smb2.Entry> l = null;
            String err = null;
            try {
                l = withConn(s, c -> c.list(tree(c, share), path));
            } catch (IOException | RuntimeException e) {
                Log.i(TAG, "list " + share + "/" + path + ": " + e);
                err = message(e);
            }
            final List<Smb2.Entry> fl = l;
            final String fErr = err;
            mMain.post(() -> {
                if (top() != p) return;
                mPanel.replace(fErr != null ? errorItems(fErr, () -> {
                    mPanel.replace(loading());
                    loadFolder(p);
                }, null) : folderItems(p, fl));
            });
        });
    }

    /** Names a NAS or a PC keeps for itself (Synology @eaDir and #recycle, Windows $RECYCLE.BIN ...). */
    static boolean skip(Smb2.Entry e) {
        String n = e.name;
        return e.hidden() || n.startsWith(".") || n.startsWith("$") || n.startsWith("@") || n.startsWith("#recycle")
                || n.startsWith("#snapshot") || "System Volume Information".equals(n);
    }

    private ArrayList<ListPanel.Item> folderItems(Page p, List<Smb2.Entry> all) {
        List<NetEntry> dirs = new ArrayList<>(), files = new ArrayList<>();
        for (Smb2.Entry e : all) {
            if (skip(e)) continue;
            NetEntry n = new NetEntry();
            n.name = e.name;
            n.type = e.dir ? UsbFiles.DIR : UsbFiles.type(e.name);
            n.size = e.size;
            (e.dir ? dirs : files).add(n);
        }
        final Collator col = Collator.getInstance();
        col.setStrength(Collator.SECONDARY);
        Collections.sort(dirs, (a, b) -> col.compare(a.name, b.name));
        Collections.sort(files, (a, b) -> col.compare(a.name, b.name));
        ArrayList<NetEntry> shown = new ArrayList<>();
        int hidden = 0;
        for (List<NetEntry> part : List.of(dirs, files)) {
            for (NetEntry e : part) {
                if (shown.size() < MAX_ENTRIES) shown.add(e);
                else hidden++;
            }
        }
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        if (shown.isEmpty()) {
            l.add(ListPanel.Item.info(R.drawable.ic_folder, str(R.string.files_empty)));
            return l;
        }
        for (NetEntry e : shown) {
            ListPanel.Item it = ListPanel.Item.action(UsbFilesActivity.icon(e.type), e.name, () -> openEntry(p, e, shown));
            if (e.type != UsbFiles.DIR) it.value(UsbFiles.size(e.size));
            it.tag = e;
            l.add(it);
        }
        if (hidden > 0) l.add(ListPanel.Item.info(0, mA.getString(R.string.files_more, hidden)));
        return l;
    }

    private Uri uri(Page p, NetEntry e) {
        return NetFilesProvider.uri(p.server.id, p.share, join(p.path, e.name), e.size);
    }

    private void openEntry(Page p, NetEntry e, List<NetEntry> folder) {
        switch (e.type) {
            case UsbFiles.DIR:
                openFolder(p.server, p.share, join(p.path, e.name), null);
                break;
            case UsbFiles.VIDEO:
            case UsbFiles.AUDIO:
                play(uri(p, e), e.name);
                break;
            case UsbFiles.IMAGE: {
                ArrayList<Uri> photos = new ArrayList<>();
                for (NetEntry x : folder) if (x.type == UsbFiles.IMAGE) photos.add(uri(p, x));
                mA.startActivity(new Intent(mA, PhotoActivity.class).setData(uri(p, e))
                        .putParcelableArrayListExtra(PhotoActivity.EXTRA_URIS, photos));
                break;
            }
            default:
                view(uri(p, e), e.name);
        }
    }

    // ------------------------------------------------------------------ opening (as UsbFilesActivity)

    /** A video or a song: the user's app for the type, else the built-in player. */
    private void play(Uri u, String name) {
        String mime = UsbFiles.mime(name);
        Intent view = viewIntent(u, mime);
        List<ResolveInfo> apps = mA.getPackageManager().queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY);
        boolean other = false;
        for (ResolveInfo r : apps) {
            if (r.activityInfo != null && !mA.getPackageName().equals(r.activityInfo.packageName)) other = true;
        }
        try {
            if (other) {
                mA.startActivity(view);
                Log.i(TAG, "view " + mime + " with " + apps.size() + " app(s)");
                return;
            }
            mA.startActivity(new Intent(mA, PlayerActivity.class).setData(u));
        } catch (ActivityNotFoundException | SecurityException t) {
            Log.w(TAG, "play " + name + ": " + t);
            Toast.makeText(mA, mA.getString(R.string.files_open_failed, name), Toast.LENGTH_LONG).show();
        }
    }

    private void view(Uri u, String name) {
        try {
            mA.startActivity(viewIntent(u, UsbFiles.mime(name)));
        } catch (ActivityNotFoundException | SecurityException t) {
            Log.i(TAG, "no app for " + name + ": " + t);
            Toast.makeText(mA, mA.getString(R.string.files_open_failed, name), Toast.LENGTH_LONG).show();
        }
    }

    private static Intent viewIntent(Uri u, String mime) {
        return new Intent(Intent.ACTION_VIEW).setDataAndType(u, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    // ------------------------------------------------------------------ the browsing connection (worker)

    /** Runs {@code op} on the browsing connection to {@code s}; once more on a new one if the old one died. */
    private <T> T withConn(NetServers.Server s, Op<T> op) throws IOException {
        boolean fresh = ensureConn(s);
        try {
            return op.run(mConn);
        } catch (IOException e) {
            if (fresh || !retryable(e)) throw e;
            Log.i(TAG, "reconnecting to " + s.host + " after " + e);
            dropConn();
            ensureConn(s);
            return op.run(mConn);
        }
    }

    private static boolean retryable(IOException e) {
        if (!(e instanceof SmbException)) return true;
        SmbException se = (SmbException) e;
        return se.sessionLost() || se.kind == SmbException.UNREACHABLE;
    }

    /** True when a new connection was made. */
    private boolean ensureConn(NetServers.Server s) throws IOException {
        if (mConn != null && !mConn.isClosed() && mConnServer != null && mConnServer.id.equals(s.id) && mConnServer.sameLogin(s)) {
            return false;
        }
        dropConn();
        mConn = Smb2.connect(s.host, s.port, s.login, s.password);
        mConnServer = s.copy();
        Log.i(TAG, "connected to " + s.host + " (SMB " + mConn.dialect() + (mConn.guest() ? ", guest" : "") + ")");
        return true;
    }

    private int tree(Smb2 c, String share) throws IOException {
        Integer t = mTrees.get(share);
        if (t != null) return t;
        int id = c.treeConnect(share);
        mTrees.put(share, id);
        return id;
    }

    private void dropConn() {
        if (mConn != null) mConn.close();
        mConn = null;
        mConnServer = null;
        mTrees.clear();
    }

    // ------------------------------------------------------------------ LAN discovery (main thread)

    private void startDiscovery() {
        if (mDisc != null) return;
        if (mNsd == null) mNsd = mA.getSystemService(NsdManager.class);
        if (mNsd == null) return;
        NsdManager.DiscoveryListener l = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String type, int code) {
                Log.i(TAG, "discovery failed: " + code);
                if (mDisc == this) mDisc = null;
            }

            @Override
            public void onStopDiscoveryFailed(String type, int code) {
            }

            @Override
            public void onDiscoveryStarted(String type) {
            }

            @Override
            public void onDiscoveryStopped(String type) {
            }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                if (mDisc != this) return;
                for (NsdServiceInfo q : mToResolve) if (q.getServiceName().equals(info.getServiceName())) return;
                for (Found f : mFound) if (f.name.equals(info.getServiceName())) return;
                mToResolve.add(info);
                resolveNext();
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
            }
        };
        try {
            mNsd.discoverServices("_smb._tcp", NsdManager.PROTOCOL_DNS_SD, (android.net.Network) null,
                    mA.getMainExecutor(), l);
            mDisc = l;
            mMain.postDelayed(mStopDiscovery, DISCOVERY_MS);
        } catch (RuntimeException e) {
            Log.i(TAG, "discovery: " + e);
        }
    }

    private void stopDiscovery() {
        mMain.removeCallbacks(mStopDiscovery);
        mToResolve.clear();
        if (mDisc == null) return;
        try {
            mNsd.stopServiceDiscovery(mDisc);
        } catch (RuntimeException ignored) {
        }
        mDisc = null;
    }

    private void resolveNext() {
        if (mResolving || mToResolve.isEmpty() || mNsd == null) return;
        NsdServiceInfo info = mToResolve.remove(0);
        mResolving = true;
        try {
            mNsd.resolveService(info, mA.getMainExecutor(), new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo i, int code) {
                    mResolving = false;
                    resolveNext();
                }

                @Override
                public void onServiceResolved(NsdServiceInfo i) {
                    mResolving = false;
                    found(i);
                    resolveNext();
                }
            });
        } catch (RuntimeException e) {
            mResolving = false;
        }
    }

    private void found(NsdServiceInfo i) {
        InetAddress pick = null;
        for (InetAddress a : i.getHostAddresses()) {
            if (a instanceof Inet4Address) {
                pick = a;
                break;
            }
            if (pick == null) pick = a;
        }
        if (pick == null) return;
        Found f = new Found();
        f.name = i.getServiceName();
        f.host = pick.getHostAddress();
        f.port = i.getPort();
        for (Found x : mFound) if (x.host.equals(f.host)) return;
        mFound.add(f);
        Log.i(TAG, "found " + f.name + " at " + f.host);
        Page t = top();
        if (t != null && t.kind == K_NET && mPanel.depth() == mPages.size() + 1) mPanel.replace(netItems());
    }
}
