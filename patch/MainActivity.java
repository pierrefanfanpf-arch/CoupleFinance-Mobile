package com.couplefinance.mobile;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int PICK_FOLDER = 3001;
    private static final int PICK_WORKBOOK = 3002;
    private static final int PICK_VIEW = 3003;
    private static final String PREFS = "cf_mobile_v2";
    private static final String PREF_TREE = "onedrive_tree_uri";
    private static final String PREF_WORKBOOK = "onedrive_workbook_uri";
    private static final String PREF_VIEW = "onedrive_view_uri";
    private static final String PREF_LINK = "onedrive_folder_link";
    private static final String WORKBOOK_NAME = "CoupleFinance_Mobile.xlsx";
    private static final String VIEW_NAME = "CoupleFinance_Mobile_View.json";

    private final Handler handler = new Handler();
    private Uri treeUri;
    private Uri workbookUri;
    private Uri viewUri;
    private JSONObject snapshot;

    private LinearLayout root;
    private LinearLayout content;
    private TextView connectionStatus;
    private TextView syncStatus;
    private EditText oneDriveLink;
    private String currentSection = "Accueil";

    private Spinner entryType, owner, category, sourceAccount, destinationAccount, debtAccount;
    private EditText amount, date, time, description, note;
    private LinearLayout destinationBlock, debtBlock;
    private final List<String> debtIds = new ArrayList<>();
    private Button saveButton;

    private final String[] sections = new String[]{
            "Accueil","Comptes","Revenus","Dépenses","Transactions","Dettes","Épargne","Agenda","Saisie"
    };

    private final Runnable autoRefresh = new Runnable() {
        @Override public void run() {
            if (treeUri != null || viewUri != null) {
                try { refreshSnapshot(false); } catch (Exception ignored) {}
            }
            handler.postDelayed(this, 30000);
        }
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildShell());
        restoreFolder();
        handler.postDelayed(autoRefresh, 30000);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(autoRefresh);
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        if (treeUri != null || viewUri != null) {
            try { refreshSnapshot(false); } catch (Exception ignored) {}
        }
    }

    private View buildShell() {
        root = vertical();
        root.setBackgroundColor(Color.rgb(248,250,253));

        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14),dp(12),dp(12),dp(12));
        GradientDrawable hg=new GradientDrawable(); hg.setColor(Color.rgb(7,57,103)); header.setBackground(hg);

        TextView menu=text("☰",26,false); menu.setTextColor(Color.WHITE); menu.setGravity(Gravity.CENTER);
        header.addView(menu,new LinearLayout.LayoutParams(dp(42),dp(52)));
        TextView heart=text("♥",36,true); heart.setTextColor(Color.rgb(255,42,82)); heart.setGravity(Gravity.CENTER);
        header.addView(heart,new LinearLayout.LayoutParams(dp(48),dp(54)));

        LinearLayout brand=vertical();
        TextView title=text("Couple Finance",24,true); title.setTextColor(Color.WHITE); brand.addView(title);
        TextView sub=text("Consultation et saisies vers PC",11,false); sub.setTextColor(Color.rgb(225,238,250)); brand.addView(sub);
        header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));

        TextView bell=text("🔔",24,true); bell.setTextColor(Color.WHITE); bell.setGravity(Gravity.CENTER);
        bell.setOnClickListener(v->toast("Notifications Couple Finance")); header.addView(bell,new LinearLayout.LayoutParams(dp(46),dp(52)));
        TextView gear=text("⚙",28,true); gear.setTextColor(Color.WHITE); gear.setGravity(Gravity.CENTER);
        gear.setOnClickListener(v->showSection("Paramètres")); header.addView(gear,new LinearLayout.LayoutParams(dp(48),dp(52)));
        root.addView(header,new LinearLayout.LayoutParams(-1,-2));

        ScrollView pageScroll=new ScrollView(this); pageScroll.setFillViewport(true);
        content=vertical(); content.setPadding(dp(12),dp(10),dp(12),dp(18));
        pageScroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        root.addView(pageScroll,new LinearLayout.LayoutParams(-1,0,1));
        root.addView(buildBottomNav(),new LinearLayout.LayoutParams(-1,dp(72)));

        connectionStatus=text("Aucun stockage cloud connecté.",12,false);
        syncStatus=text("",11,false);
        oneDriveLink=input("https://1drv.ms/... ou https://onedrive.live.com/...");
        oneDriveLink.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_LINK, ""));
        showSection("Accueil");
        return root;
    }

    private View buildBottomNav(){
        LinearLayout nav=horizontal(); nav.setGravity(Gravity.CENTER); nav.setPadding(dp(4),dp(4),dp(4),dp(4));
        nav.setBackgroundColor(Color.WHITE); nav.setElevation(dp(10));
        nav.addView(bottomButton("⌂","Accueil","Accueil",false),weight());
        nav.addView(bottomButton("🛒","Dépenses","Dépenses",false),weight());
        nav.addView(bottomButton("＋","Saisie","Saisie",true),weight());
        nav.addView(bottomButton("▣","Comptes","Comptes",false),weight());
        nav.addView(bottomButton("▦","Plus","Plus",false),weight());
        return nav;
    }

    private Button bottomButton(String icon,String label,String section,boolean center){
        Button b=button(icon+"\n"+label); b.setGravity(Gravity.CENTER); b.setTextSize(center?12:11);
        b.setTextColor(center?Color.WHITE:Color.rgb(66,76,88)); b.setPadding(dp(2),dp(2),dp(2),dp(2));
        GradientDrawable g=new GradientDrawable(); g.setColor(center?Color.rgb(18,102,225):Color.WHITE); g.setCornerRadius(dp(center?32:4)); b.setBackground(g);
        if(center)b.setElevation(dp(7)); b.setOnClickListener(v->showSection(section)); return b;
    }

    private void saveOneDriveLink() {
        String link = oneDriveLink == null ? "" : oneDriveLink.getText().toString().trim();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_LINK, link).apply();
        toast(link.isEmpty() ? "Lien OneDrive effacé." : "Lien OneDrive mémorisé.");
    }

    private void openOneDriveLink() {
        String link = oneDriveLink == null ? "" : oneDriveLink.getText().toString().trim();
        if (link.isEmpty()) { toast("Entre d'abord le lien du dossier OneDrive."); return; }
        if (!(link.startsWith("https://") || link.startsWith("http://"))) { link = "https://" + link; oneDriveLink.setText(link); }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_LINK, link).apply();
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link))); }
        catch (Exception e) { toast("Impossible d'ouvrir ce lien OneDrive : " + e.getMessage()); }
    }

    private void chooseWorkbookFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, PICK_WORKBOOK);
    }

    private void chooseViewFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, PICK_VIEW);
    }

    private void chooseFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, PICK_FOLDER);
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { getContentResolver().takePersistableUriPermission(uri, flags); } catch (Exception ignored) {}
        SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();

        if (req == PICK_FOLDER) {
            treeUri = uri; ed.putString(PREF_TREE, uri.toString()).apply();
            try { locateFiles(); ensureWorkbook(); refreshSnapshot(true); }
            catch (Exception e) { setConnection(false, "Erreur stockage cloud : " + e.getMessage()); }
            return;
        }
        if (req == PICK_WORKBOOK) {
            workbookUri = uri; ed.putString(PREF_WORKBOOK, uri.toString()).apply();
            setConnection(true, "Excel cloud sélectionné."); toast("Fichier Excel cloud mémorisé."); return;
        }
        if (req == PICK_VIEW) {
            viewUri = uri; ed.putString(PREF_VIEW, uri.toString()).apply();
            setConnection(true, "Vue PC cloud sélectionnée."); refreshSnapshot(true);
        }
    }

    private void restoreFolder() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String t = sp.getString(PREF_TREE, ""), w = sp.getString(PREF_WORKBOOK, ""), v = sp.getString(PREF_VIEW, ""), link = sp.getString(PREF_LINK, "");
        if (oneDriveLink != null) oneDriveLink.setText(link);
        if (!t.isEmpty()) treeUri = Uri.parse(t);
        if (!w.isEmpty()) workbookUri = Uri.parse(w);
        if (!v.isEmpty()) viewUri = Uri.parse(v);
        try {
            if (treeUri != null) locateFiles();
            if (treeUri != null && workbookUri == null) ensureWorkbook();
            if (viewUri != null || treeUri != null) refreshSnapshot(false);
            else if (workbookUri != null) setConnection(true, "Excel cloud mémorisé. Choisis maintenant la vue PC JSON.");
        } catch (Exception e) { setConnection(false, "Stockage cloud mémorisé inaccessible : " + e.getMessage()); }
    }

    private Uri treeDocumentUri() {
        String id = DocumentsContract.getTreeDocumentId(treeUri);
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
    }

    private void locateFiles() throws Exception {
        if (treeUri == null) return;
        Uri wb = findChild(WORKBOOK_NAME), vw = findChild(VIEW_NAME);
        if (wb != null) workbookUri = wb;
        if (vw != null) viewUri = vw;
        setConnection(true, "Dossier cloud connecté.");
    }

    private Uri findChild(String name) throws Exception {
        if (treeUri == null) return null;
        String parentId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        String[] projection = new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        try (Cursor c = getContentResolver().query(children, projection, null, null, null)) {
            if (c != null) {
                int idIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                while (c.moveToNext()) {
                    String display = c.getString(nameIndex);
                    if (name.equalsIgnoreCase(display)) {
                        String docId = c.getString(idIndex);
                        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                    }
                }
            }
        }
        return null;
    }

    private void ensureWorkbook() throws Exception {
        if (workbookUri != null) return;
        Uri created = DocumentsContract.createDocument(
                getContentResolver(),
                treeDocumentUri(),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                WORKBOOK_NAME);
        if (created == null) throw new Exception("Impossible de créer " + WORKBOOK_NAME);
        try (InputStream in = getAssets().open(WORKBOOK_NAME);
             OutputStream out = getContentResolver().openOutputStream(created, "wt")) {
            if (out == null) throw new Exception("OneDrive refuse l'écriture du fichier Excel.");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        }
        workbookUri = created;
    }

    private void refreshSnapshot(boolean userMessage) {
        if (treeUri == null && viewUri == null) {
            if (userMessage) toast("Choisis le dossier OneDrive ou le fichier CoupleFinance_Mobile_View.json.");
            return;
        }
        try {
            if (treeUri != null) {
                locateFiles();
                if (workbookUri == null) ensureWorkbook();
                Uri detectedView = findChild(VIEW_NAME);
                if (detectedView != null) viewUri = detectedView;
            }
            if (viewUri == null) {
                snapshot = null;
                if(syncStatus!=null)syncStatus.setText("La vue lecture seule n'est pas encore disponible. Ouvrez Couple Finance sur le PC puis sauvegardez/actualisez la vue téléphone.");
                if (userMessage) toast("Fichier de vue non trouvé dans OneDrive.");
                showSection(currentSection);
                return;
            }
            String raw = readText(viewUri);
            snapshot = new JSONObject(raw);
            String gen = snapshot.optString("generatedAt", "—");
            String period = snapshot.optString("period", "—");
            if(syncStatus!=null)syncStatus.setText("Vue PC : " + gen + "   •   " + period);
            setConnection(true, "OneDrive connecté • Excel de saisie + vue PC détectés");
            showSection(currentSection);
            if (userMessage) toast("Vue Couple Finance actualisée.");
        } catch (Exception e) {
            setConnection(false, "Actualisation impossible : " + e.getMessage());
            if (userMessage) toast(e.getMessage());
        }
    }

    private String readText(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new Exception("Impossible d'ouvrir la vue Couple Finance.");
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8).replace("\uFEFF", "");
        }
    }

    private void setConnection(boolean ok, String msg) {
        if(connectionStatus!=null){connectionStatus.setText((ok ? "✓ " : "⚠ ") + msg);connectionStatus.setTextColor(ok ? Color.rgb(24,137,91) : Color.rgb(190,55,55));}
    }

    private void showSection(String section) {
        currentSection=section;if(content==null)return;content.removeAllViews();
        if("Accueil".equals(section)){renderHome();return;}
        if("Paramètres".equals(section)){renderSettings();return;}
        if("Plus".equals(section)){renderMore();return;}
        TextView h=text(section,22,true);h.setTextColor(Color.rgb(7,51,94));h.setPadding(dp(2),dp(8),0,dp(4));content.addView(h);
        if("Saisie".equals(section)){buildEntryForm();return;}
        TextView ro=text("Dépenses".equals(section)?"Lecture seule, sauf l’action Reporter sur une échéance.":"Lecture seule — les données principales proviennent de l'application PC.",11,false);
        ro.setTextColor(Color.GRAY);content.addView(ro);spacer(content,8);
        if(snapshot==null){content.addView(infoCard("Aucune donnée","Connecte le stockage cloud dans Plus → Paramètres, puis actualise la vue PC."));return;}
        switch(section){
            case "Comptes":renderAccounts();break;case "Revenus":renderIncome();break;case "Dépenses":renderExpenses();break;
            case "Transactions":renderTransactions();break;case "Dettes":renderDebtsGoals();break;case "Épargne":renderSavings();break;
            case "Agenda":renderAgenda();break;case "Budget":renderBudgetMobile();break;case "Objectifs":renderGoalsMobile();break;
            case "Projections":renderProjectionMobile();break;case "Rapports":renderReportsMobile();break;default:renderHome();
        }
    }

    private void renderHome(){
        LinearLayout cloud=softCard(Color.rgb(244,252,247),Color.rgb(210,235,218));
        LinearLayout cr=horizontal();cr.setGravity(Gravity.CENTER_VERTICAL);
        TextView cloudIcon=text("☁",34,true);cloudIcon.setTextColor(Color.rgb(12,132,222));cloudIcon.setGravity(Gravity.CENTER);
        cr.addView(cloudIcon,new LinearLayout.LayoutParams(dp(66),dp(72)));
        LinearLayout ci=vertical();boolean connected=(treeUri!=null||workbookUri!=null||viewUri!=null);
        TextView ct=text(connected?"✓  OneDrive connecté":"○  Stockage cloud à connecter",16,true);ct.setTextColor(connected?Color.rgb(18,112,49):Color.rgb(110,118,128));ci.addView(ct);
        TextView folder=text("Dossier : Couple Finance",12,false);folder.setTextColor(Color.GRAY);ci.addView(folder);
        TextView xf=text((workbookUri!=null?"✓ ":"○ ")+WORKBOOK_NAME,11,false);xf.setTextColor(workbookUri!=null?Color.rgb(20,120,58):Color.GRAY);ci.addView(xf);
        TextView jf=text((viewUri!=null?"✓ ":"○ ")+VIEW_NAME,11,false);jf.setTextColor(viewUri!=null?Color.rgb(20,120,58):Color.GRAY);ci.addView(jf);
        cr.addView(ci,new LinearLayout.LayoutParams(0,-2,1));
        Button change=smallPill("▣  Changer");change.setOnClickListener(v->showSection("Paramètres"));cr.addView(change,new LinearLayout.LayoutParams(dp(105),dp(48)));
        cloud.addView(cr);content.addView(cloud);

        LinearLayout period=horizontal();period.setGravity(Gravity.CENTER_VERTICAL);period.setPadding(0,dp(7),0,dp(7));
        Button prev=smallPill("‹");prev.setOnClickListener(v->toast("La période affichée suit la période sélectionnée sur le PC."));period.addView(prev,new LinearLayout.LayoutParams(dp(42),dp(48)));
        String per=snapshot==null?"Période PC":snapshot.optString("period","Période PC");
        Button pb=smallPill("▣  "+per+" ⌄");period.addView(pb,new LinearLayout.LayoutParams(0,dp(48),1));
        Button refresh=smallPill("↻ Actualiser");refresh.setOnClickListener(v->refreshSnapshot(true));period.addView(refresh,new LinearLayout.LayoutParams(dp(104),dp(48)));
        TextView synced=text(snapshot!=null?"✓ Synchronisé":"○ Hors ligne",10,true);synced.setGravity(Gravity.CENTER);synced.setTextColor(snapshot!=null?Color.rgb(20,120,58):Color.GRAY);
        GradientDrawable sg=new GradientDrawable();sg.setColor(snapshot!=null?Color.rgb(235,249,239):Color.rgb(242,244,247));sg.setCornerRadius(dp(18));synced.setBackground(sg);
        LinearLayout.LayoutParams spp=new LinearLayout.LayoutParams(dp(88),dp(42));spp.setMargins(dp(4),0,0,0);period.addView(synced,spp);content.addView(period);

        JSONObject sm=snapshot==null?null:snapshot.optJSONObject("summary");if(sm==null)sm=new JSONObject();
        double inc=sm.optDouble("incomeReceived",0),exp=sm.optDouble("expenseActual",0),planned=sm.optDouble("expensePlanned",0),solde=inc-exp,saveRate=inc>0?Math.max(0,solde/inc*100):0;
        LinearLayout kpis=horizontal();
        kpis.addView(homeKpi("▰ Revenus",money(inc),"Prévu : "+money(sm.optDouble("incomeExpected",inc)),Color.rgb(233,245,255),Color.rgb(18,92,190)),weight());
        kpis.addView(homeKpi("🛒 Dépenses",money(exp),"Budget : "+money(planned),Color.rgb(255,238,242),Color.rgb(190,31,53)),weight());
        kpis.addView(homeKpi("▥ Solde du mois",money(solde),"Épargne : "+Math.round(saveRate)+" %",Color.rgb(236,249,238),Color.rgb(20,115,43)),weight());content.addView(kpis);

        LinearLayout mini=horizontal();
        mini.addView(summaryTile("▥","Comptes",String.valueOf(arrayLen("accounts")),"Voir le détail","Comptes"),weight());
        mini.addView(summaryTile("▣","Dettes",money(sm.optDouble("totalDebt",0)),"Voir le détail","Dettes"),weight());
        mini.addView(summaryTile("◎","Objectifs",String.valueOf(arrayLen("goals")),"Voir la progression","Objectifs"),weight());
        mini.addView(summaryTile("◉","Analyse IA","Conseils","Voir recommandations","Plus"),weight());content.addView(mini);

        String[][] menu={{"⌂","Accueil","Accueil"},{"▣","Comptes","Comptes"},{"●","Revenus","Revenus"},{"🛒","Dépenses","Dépenses"},{"◉","Dettes","Dettes"},{"◔","Budget","Budget"},{"◎","Objectifs","Objectifs"},{"▥","Projections","Projections"},{"▤","Rapports","Rapports"},{"⚙","Paramètres","Paramètres"}};
        for(int r=0;r<2;r++){LinearLayout row=horizontal();for(int j=0;j<5;j++){int ix=r*5+j;row.addView(menuTile(menu[ix][0],menu[ix][1],menu[ix][2],ix==0),weight());}content.addView(row);}
        renderUpcomingHome();renderExpenseDistributionHome(exp);
    }

    private int arrayLen(String key){JSONArray a=snapshot==null?null:snapshot.optJSONArray(key);return a==null?0:a.length();}

    private View homeKpi(String title,String value,String sub,int bg,int fg){
        LinearLayout c=softCard(bg,bg);c.setPadding(dp(8),dp(12),dp(7),dp(12));TextView t=text(title,11,true);t.setTextColor(fg);c.addView(t);
        TextView v=text(value,18,true);v.setTextColor(fg);v.setPadding(0,dp(8),0,dp(3));c.addView(v);TextView x=text(sub,9,false);x.setTextColor(fg);c.addView(x);return c;
    }
    private View summaryTile(String icon,String title,String value,String sub,String section){
        LinearLayout c=softCard(Color.WHITE,Color.rgb(226,232,240));c.setPadding(dp(5),dp(8),dp(5),dp(8));c.setGravity(Gravity.CENTER);
        TextView i=text(icon,18,true);i.setTextColor(Color.rgb(9,59,106));i.setGravity(Gravity.CENTER);c.addView(i);
        TextView t=text(title,9,true);t.setTextColor(Color.rgb(9,48,91));t.setGravity(Gravity.CENTER);c.addView(t);
        TextView v=text(value,12,true);v.setTextColor(Color.rgb(5,35,74));v.setGravity(Gravity.CENTER);c.addView(v);
        TextView x=text(sub,8,false);x.setTextColor(Color.GRAY);x.setGravity(Gravity.CENTER);c.addView(x);c.setOnClickListener(vw->showSection(section));return c;
    }
    private View menuTile(String icon,String label,String section,boolean active){
        LinearLayout c=softCard(active?Color.rgb(232,244,255):Color.WHITE,active?Color.rgb(135,191,250):Color.rgb(226,232,240));c.setPadding(dp(2),dp(9),dp(2),dp(9));c.setGravity(Gravity.CENTER);
        TextView i=text(icon,19,true);i.setGravity(Gravity.CENTER);i.setTextColor(active?Color.rgb(18,91,195):Color.rgb(8,59,106));c.addView(i);
        TextView t=text(label,8,active);t.setGravity(Gravity.CENTER);t.setTextColor(active?Color.rgb(18,91,195):Color.rgb(8,35,73));c.addView(t);c.setOnClickListener(v->showSection(section));return c;
    }

    private void renderUpcomingHome(){
        LinearLayout card=softCard(Color.WHITE,Color.rgb(226,232,240));LinearLayout hr=horizontal();TextView h=text("Prochaines échéances",18,true);h.setTextColor(Color.rgb(7,51,94));hr.addView(h,new LinearLayout.LayoutParams(0,-2,1));
        Button all=smallPill("Voir tout ›");all.setOnClickListener(v->showSection("Dépenses"));hr.addView(all,new LinearLayout.LayoutParams(dp(92),dp(40)));card.addView(hr);
        JSONArray a=snapshot==null?null:snapshot.optJSONArray("recurringView");if(a==null||a.length()==0)a=snapshot==null?null:snapshot.optJSONArray("upcoming");
        if(a==null||a.length()==0){TextView e=text("Aucune échéance disponible.",11,false);e.setTextColor(Color.GRAY);e.setPadding(0,dp(10),0,dp(6));card.addView(e);}
        else for(int i=0;i<Math.min(4,a.length());i++){final JSONObject x=a.optJSONObject(i);if(x==null)continue;LinearLayout row=horizontal();row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(7),0,dp(7));
            String ds=x.optString("dueDate",x.optString("date","—"));TextView day=text(shortDate(ds),10,true);day.setGravity(Gravity.CENTER);day.setTextColor(Color.rgb(170,35,45));GradientDrawable dg=new GradientDrawable();dg.setColor(Color.rgb(255,239,242));dg.setCornerRadius(dp(9));day.setBackground(dg);row.addView(day,new LinearLayout.LayoutParams(dp(54),dp(50)));
            LinearLayout info=vertical();TextView n=text(x.optString("name",x.optString("desc","Échéance")),12,true);n.setTextColor(Color.rgb(7,45,84));info.addView(n);TextView sub=text(x.optString("owner",x.optString("cat","Commun")),9,false);sub.setTextColor(Color.GRAY);info.addView(sub);row.addView(info,new LinearLayout.LayoutParams(0,-2,1));
            TextView amt=text(money(x.optDouble("amount",0)),11,true);amt.setTextColor(Color.rgb(7,45,84));amt.setGravity(Gravity.RIGHT);row.addView(amt,new LinearLayout.LayoutParams(dp(78),-2));
            if(x.has("id")||x.has("period")){Button rep=smallPill("▣ Reporter");rep.setOnClickListener(v->chooseDeferralDate(x));row.addView(rep,new LinearLayout.LayoutParams(dp(88),dp(42)));}card.addView(row);
        }content.addView(card);
    }
    private String shortDate(String s){try{Date d=new SimpleDateFormat("yyyy-MM-dd",Locale.CANADA_FRENCH).parse(s);return new SimpleDateFormat("dd\nMMM",Locale.CANADA_FRENCH).format(d).replace(".","");}catch(Exception e){return s.length()>5?s.substring(Math.max(0,s.length()-5)):s;}}

    private void renderExpenseDistributionHome(double total){
        LinearLayout card=softCard(Color.WHITE,Color.rgb(226,232,240));LinearLayout hr=horizontal();TextView h=text("Répartition des dépenses",18,true);h.setTextColor(Color.rgb(7,51,94));hr.addView(h,new LinearLayout.LayoutParams(0,-2,1));
        Button all=smallPill("Voir le détail");all.setOnClickListener(v->showSection("Dépenses"));hr.addView(all,new LinearLayout.LayoutParams(dp(104),dp(40)));card.addView(hr);
        JSONArray b=snapshot==null?null:snapshot.optJSONArray("budgets");List<Double> vals=new ArrayList<>();List<String> labels=new ArrayList<>();double sum=0;
        if(b!=null)for(int i=0;i<b.length()&&i<6;i++){JSONObject x=b.optJSONObject(i);if(x==null)continue;double v=Math.max(0,x.optDouble("spent",0));if(v<=0)continue;vals.add(v);labels.add(x.optString("cat","Autres"));sum+=v;}
        if(vals.isEmpty()){vals.add(Math.max(1,total));labels.add("Dépenses");sum=Math.max(1,total);}
        LinearLayout body=horizontal();ExpenseDonutView donut=new ExpenseDonutView(vals,sum,total);body.addView(donut,new LinearLayout.LayoutParams(dp(145),dp(145)));
        LinearLayout legend=vertical();int[] cs={Color.rgb(51,143,238),Color.rgb(246,53,78),Color.rgb(91,174,100),Color.rgb(255,177,47),Color.rgb(132,79,221),Color.rgb(70,194,120)};
        for(int i=0;i<vals.size();i++){double pct=sum>0?vals.get(i)/sum*100:0;TextView l=text("● "+labels.get(i)+"  "+Math.round(pct)+"%  "+money(vals.get(i)),9,false);l.setTextColor(cs[i%cs.length]);legend.addView(l);}
        body.addView(legend,new LinearLayout.LayoutParams(0,-2,1));card.addView(body);content.addView(card);
    }

    private void renderSettings(){
        TextView h=text("Paramètres",22,true);h.setTextColor(Color.rgb(7,51,94));content.addView(h);TextView sh=text("Stockage cloud",16,true);sh.setTextColor(Color.rgb(7,51,94));sh.setPadding(0,dp(8),0,dp(4));content.addView(sh);
        LinearLayout c=softCard(Color.WHITE,Color.rgb(220,229,238));oneDriveLink=input("https://1drv.ms/... ou https://onedrive.live.com/...");oneDriveLink.setText(getSharedPreferences(PREFS,MODE_PRIVATE).getString(PREF_LINK,""));addLabeled(c,"Lien du dossier OneDrive (optionnel)",oneDriveLink);
        LinearLayout lr=horizontal();Button save=smallPill("Mémoriser le lien");save.setOnClickListener(v->saveOneDriveLink());lr.addView(save,weight());Button open=smallPill("Ouvrir OneDrive");open.setOnClickListener(v->openOneDriveLink());lr.addView(open,weight());c.addView(lr);
        Button folder=primaryButton("Choisir dossier OneDrive / Google Drive");folder.setOnClickListener(v->chooseFolder());c.addView(folder,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout fr=horizontal();Button excel=smallPill("Choisir Excel");excel.setOnClickListener(v->chooseWorkbookFile());fr.addView(excel,weight());Button json=smallPill("Choisir vue PC JSON");json.setOnClickListener(v->chooseViewFile());fr.addView(json,weight());c.addView(fr);
        Button refresh=primaryButton("↻ Actualiser maintenant");refresh.setOnClickListener(v->refreshSnapshot(true));c.addView(refresh,new LinearLayout.LayoutParams(-1,-2));
        connectionStatus=text((treeUri!=null||workbookUri!=null||viewUri!=null)?"✓ Stockage cloud mémorisé":"Aucun stockage cloud connecté.",12,false);connectionStatus.setTextColor((treeUri!=null||workbookUri!=null||viewUri!=null)?Color.rgb(24,137,91):Color.GRAY);connectionStatus.setPadding(0,dp(8),0,dp(2));c.addView(connectionStatus);
        syncStatus=text(snapshot==null?"Vue PC non chargée.":"Vue PC : "+snapshot.optString("generatedAt","—")+" • "+snapshot.optString("period","—"),11,false);syncStatus.setTextColor(Color.GRAY);c.addView(syncStatus);content.addView(c);
    }
    private void renderMore(){
        TextView h=text("Plus",22,true);h.setTextColor(Color.rgb(7,51,94));content.addView(h);
        String[][] items={{"⇄","Transactions","Transactions"},{"◎","Épargne","Épargne"},{"▦","Agenda","Agenda"},{"◔","Budget","Budget"},{"◎","Objectifs","Objectifs"},{"▥","Projections","Projections"},{"▤","Rapports","Rapports"},{"⚙","Paramètres","Paramètres"}};
        for(int r=0;r<4;r++){LinearLayout row=horizontal();for(int j=0;j<2;j++){int ix=r*2+j;row.addView(menuTile(items[ix][0],items[ix][1],items[ix][2],false),weight());}content.addView(row);}
    }
    private void renderBudgetMobile(){renderArray("budgets",80,x->x.optString("cat","Catégorie"),x->"Réel : "+money(x.optDouble("spent",0))+" • Budget : "+money(x.optDouble("budget",0)));}
    private void renderGoalsMobile(){renderArray("goals",80,x->x.optString("name","Objectif"),x->"Cible : "+money(x.optDouble("price",0))+" • Épargné : "+money(x.optDouble("saved",0)));}
    private void renderProjectionMobile(){JSONObject sm=snapshot.optJSONObject("summary");if(sm==null){emptyState("Projection non disponible.");return;}content.addView(kpiCard("Solde projeté",money(sm.optDouble("incomeExpected",0)-sm.optDouble("expensePlanned",0)),"Revenus prévus moins dépenses prévues",true));renderArray("monthlySeries",24,x->x.optString("period",x.optString("month","Période")),x->"Revenus : "+money(x.optDouble("income",x.optDouble("hi",0)+x.optDouble("wi",0)+x.optDouble("ci",0)))+" • Dépenses : "+money(x.optDouble("expense",x.optDouble("he",0)+x.optDouble("we",0)+x.optDouble("ce",0))));}
    private void renderReportsMobile(){content.addView(infoCard("Rapports","Les rapports complets restent générés sur l'application PC. La vue mobile présente les données synchronisées les plus récentes."));}
    private LinearLayout softCard(int bg,int stroke){LinearLayout c=vertical();c.setPadding(dp(12),dp(10),dp(12),dp(10));GradientDrawable g=new GradientDrawable();g.setColor(bg);g.setCornerRadius(dp(16));g.setStroke(dp(1),stroke);c.setBackground(g);c.setElevation(dp(1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(dp(2),dp(5),dp(2),dp(5));c.setLayoutParams(p);return c;}
    private Button smallPill(String x){Button b=button(x);b.setTextSize(10);b.setTextColor(Color.rgb(18,82,170));b.setPadding(dp(5),dp(3),dp(5),dp(3));GradientDrawable g=new GradientDrawable();g.setColor(Color.rgb(239,246,255));g.setCornerRadius(dp(14));g.setStroke(dp(1),Color.rgb(221,232,246));b.setBackground(g);return b;}

    private void addCountSummary(String label, String key) {
        JSONArray a = snapshot.optJSONArray(key);
        content.addView(infoCard(label, (a == null ? 0 : a.length()) + " élément(s) dans la vue PC."));
    }

    private void renderAccounts() {
        JSONArray a = snapshot.optJSONArray("accounts");
        if (empty(a)) { emptyState("Aucun compte."); return; }
        for (int i=0;i<a.length();i++) {
            JSONObject x=a.optJSONObject(i); if(x==null)continue;
            double bal=x.optDouble("balance",0);
            String body=(x.optString("owner","Commun")+" • "+x.optString("institution","")+"\n"+
                    x.optString("type","Compte")+"\nSolde : "+money(bal));
            if(x.optDouble("limit",0)>0)body+="\nLimite : "+money(x.optDouble("limit",0))+" • Disponible : "+money(x.optDouble("available",0));
            if(x.optDouble("debt",0)>0)body+="\nDette liée : "+money(x.optDouble("debt",0));
            if("Carte de crédit".equalsIgnoreCase(x.optString("type",""))){
                body+="\nMinimum demandé : "+money(x.optDouble("minPayment",0));
                if(!x.isNull("minPaymentRemaining"))body+=" • Reste ce mois : "+money(x.optDouble("minPaymentRemaining",0));
            }
            content.addView(infoCard(x.optString("name","Compte"),body));
        }
    }

    private void renderIncome() {
        JSONObject s=snapshot.optJSONObject("summary");
        if(s!=null)content.addView(kpiCard("Revenus du mois",money(s.optDouble("incomeReceived",0)),"À recevoir : "+money(s.optDouble("incomeExpected",0)),true));
        content.addView(sectionLabel("Sources de revenus"));
        renderArray("incomeSources", 50, x ->
                x.optString("owner","Commun")+" • "+x.optString("name","Revenu"),
                x -> "Type : "+x.optString("type","")+" • Fréquence : "+x.optString("frequency","")+"\nMontant : "+money(x.optDouble("lastAmount",0))+" • "+(x.optBoolean("active",true)?"Active":"Inactive"));
        content.addView(sectionLabel("Historique des revenus"));
        renderArrayReverse("incomeEntries", 80, x ->
                x.optString("date","")+" • "+x.optString("owner","Commun")+" • "+x.optString("sourceName",x.optString("source","Revenu")),
                x -> money(x.optDouble("amount",0))+" • "+x.optString("status",""));
        content.addView(sectionLabel("RQAP & allocations"));
        renderArray("benefits", 50, x ->
                x.optString("name",x.optString("type","Prestation")),
                x -> x.optString("owner","Commun")+" • "+x.optString("type","")+"\nMontant : "+money(x.optDouble("amount",x.optDouble("weeklyIncome",0)))+" • "+(x.optBoolean("active",true)?"Active":"Inactive"));
    }

    private void renderExpenses() {
        JSONObject sm=snapshot.optJSONObject("summary");
        if(sm!=null)content.addView(kpiCard("Dépenses du mois",money(sm.optDouble("expenseActual",0)),"Total prévu : "+money(sm.optDouble("expensePlanned",0)),false));
        content.addView(sectionLabel("Budget"));
        renderArray("budgets", 60, x ->
                x.optString("cat","Catégorie"),
                x -> "Réel : "+money(x.optDouble("spent",0))+" • Prévu : "+money(x.optDouble("budget",0)));

        content.addView(sectionLabel("Dépenses constantes / paiements"));
        JSONArray a=snapshot.optJSONArray("recurringView");
        if(a==null){
            renderArray("recurring", 60, x ->
                    x.optString("name","Dépense constante"),
                    x -> x.optString("owner","Commun")+" • "+x.optString("cat","Autres")+"\n"+money(x.optDouble("amount",0)));
            return;
        }
        if(a.length()==0){emptyState("Aucune dépense constante active.");return;}
        for(int i=0;i<a.length();i++){
            final JSONObject x=a.optJSONObject(i); if(x==null)continue;
            LinearLayout c=cardBox();
            TextView t=text(x.optString("name","Dépense constante"),15,true);t.setTextColor(Color.rgb(7,51,94));c.addView(t);
            String state=x.optBoolean("posted",false)?"Prise en compte":(x.optBoolean("deferred",false)?"Reportée":"À venir");
            String body=x.optString("owner","Commun")+" • "+x.optString("category","Autres")+"\nMontant / reste : "+money(x.optDouble("amount",0))+
                    "\nÉchéance : "+x.optString("dueDate","—")+" • "+state;
            if(!x.isNull("minimumRemaining"))body+="\nMinimum carte restant : "+money(x.optDouble("minimumRemaining",0));
            TextView b=text(body,12,false);b.setTextColor(Color.DKGRAY);b.setPadding(0,dp(4),0,dp(6));c.addView(b);
            Button report=button(x.optBoolean("posted",false)?"Reporter / retirer du mois":"Reporter");
            report.setOnClickListener(v->chooseDeferralDate(x));
            c.addView(report,new LinearLayout.LayoutParams(-1,-2));
            content.addView(c);
        }
    }

    private void chooseDeferralDate(JSONObject rec) {
        String due=rec.optString("dueDate", isoDate());
        Calendar cal=Calendar.getInstance();
        try{
            Date d=new SimpleDateFormat("yyyy-MM-dd",Locale.CANADA_FRENCH).parse(due);
            if(d!=null)cal.setTime(d);
        }catch(Exception ignored){}
        new DatePickerDialog(this,(v,y,m,d)->{
            String nd=String.format(Locale.CANADA_FRENCH,"%04d-%02d-%02d",y,m+1,d);
            writeDeferralCommand(rec,nd);
        },cal.get(Calendar.YEAR),cal.get(Calendar.MONTH),cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private boolean ensureWorkbookForWrite() {
        if(treeUri==null && workbookUri==null){toast("Choisis d'abord le fichier Excel OneDrive ou Google Drive.");return false;}
        try{
            if(treeUri!=null){locateFiles();if(workbookUri==null)ensureWorkbook();}
            if(workbookUri==null)throw new Exception("CoupleFinance_Mobile.xlsx n'est pas sélectionné.");
            return true;
        }catch(Exception e){toast("Excel mobile inaccessible : "+e.getMessage());return false;}
    }

    private void writeDeferralCommand(JSONObject rec,String newDate) {
        if(!ensureWorkbookForWrite())return;
        XlsxAppender.Entry e=new XlsxAppender.Entry();
        e.date=isoDate();e.time=hmTime();e.owner=rec.optString("owner","Commun");e.type="Reporter paiement";e.amount=0;
        e.category="Report";e.description=rec.optString("name","Paiement reporté");e.account="";
        e.note="RECURRENCE_ID="+rec.optString("id","")+"; NEWDATE="+newDate+"; PERIOD="+rec.optString("period","");
        new Thread(()->{
            try{
                XlsxAppender.append(getContentResolver(),workbookUri,e);
                runOnUiThread(()->toast("Report envoyé au PC pour le "+newDate+"."));
            }catch(Exception ex){runOnUiThread(()->toast("Erreur d'écriture : "+ex.getMessage()));}
        }).start();
    }

    private void renderTransactions() {
        JSONArray a=snapshot.optJSONArray("transactions");
        if(empty(a)){emptyState("Aucune transaction.");return;}
        int shown=0;
        for(int i=a.length()-1;i>=0 && shown<120;i--,shown++){
            JSONObject x=a.optJSONObject(i);if(x==null)continue;
            double amt=x.optDouble("amount",0);
            String body=x.optString("owner","Commun")+" • "+x.optString("cat","Autres")+" • "+x.optString("origin",x.optString("source",""))+
                    "\n"+(amt<0?"− ":"+ ")+money(Math.abs(amt))+" • "+x.optString("operation",x.optString("type","Transaction"));
            content.addView(infoCard(x.optString("date","")+" • "+x.optString("desc","Transaction"),body));
        }
        content.addView(sectionLabel("Transferts de comptes"));
        renderArrayReverse("accountTransfers",50,x ->
                x.optString("date","")+" • Transfert",
                x -> money(x.optDouble("amount",0))+" • "+x.optString("note",""));
    }

    private void renderDebtsGoals() {
        content.addView(sectionLabel("Dettes"));
        renderArray("debts",80,x ->
                x.optString("owner","Commun")+" • "+x.optString("name","Dette"),
                x -> debtBody(x));
        content.addView(sectionLabel("Objectifs"));
        renderArray("goals",80,x ->
                x.optString("name","Objectif"),
                x -> "Objectif : "+money(x.optDouble("price",0))+" • Épargné : "+money(x.optDouble("saved",0))+"\nMode : "+x.optString("mode",""));
    }

    private String debtBody(JSONObject x){
        String body="Solde : "+money(x.optDouble("balance",0))+" • Taux : "+fmt(x.optDouble("rate",0))+" %\nMinimum : "+money(x.optDouble("min",0))+" • Échéance : "+x.optString("dueDate","—");
        String aid=x.optString("sourceAccountId","");
        JSONArray acc=snapshot==null?null:snapshot.optJSONArray("accounts");
        if(acc!=null&&!aid.isEmpty())for(int i=0;i<acc.length();i++){JSONObject a=acc.optJSONObject(i);if(a!=null&&aid.equals(a.optString("id",""))&&!a.isNull("minPaymentRemaining")){body+="\nReste minimum ce mois : "+money(a.optDouble("minPaymentRemaining",0));break;}}
        return body;
    }

    private void renderSavings() {
        content.addView(sectionLabel("Placements"));
        renderArray("investments",80,x ->
                x.optString("owner","Commun")+" • "+x.optString("name","Placement"),
                x -> x.optString("type","")+" • "+x.optString("institution","")+"\nValeur : "+money(x.optDouble("current",0))+" • Contribution : "+money(x.optDouble("contribution",0))+" "+x.optString("frequency",""));
        content.addView(sectionLabel("Objectifs d'épargne"));
        renderArray("goals",80,x ->
                x.optString("name","Objectif"),
                x -> "Valeur cible : "+money(x.optDouble("price",0))+" • Déjà accumulé : "+money(x.optDouble("saved",0)));
    }

    private void renderAgenda() {
        content.addView(sectionLabel("Tâches du mois"));
        renderArray("monthTasks",100,x ->
                x.optString("date","")+" • "+x.optString("name","Tâche"),
                x -> x.optString("owner","Commun")+" • "+(x.optBoolean("done",false)?"Fait":"À faire")+" • "+money(x.optDouble("amount",0)));
        content.addView(sectionLabel("Événements"));
        renderArray("agendaEvents",100,x ->
                x.optString("date","")+" • "+x.optString("name","Événement"),
                x -> x.optString("owner","Commun")+" • "+x.optString("status","Prévu")+" • "+money(x.optDouble("amount",0)));
        content.addView(sectionLabel("À venir"));
        renderArray("upcoming",100,x ->
                x.optString("date","")+" • "+x.optString("name","À venir"),
                x -> x.optString("cat","")+" • "+money(x.optDouble("amount",0)));
    }

    private interface TitleMaker { String make(JSONObject x); }
    private interface BodyMaker { String make(JSONObject x); }

    private void renderArray(String key,int max,TitleMaker tm,BodyMaker bm){
        JSONArray a=snapshot.optJSONArray(key);
        if(empty(a)){emptyState("Aucune donnée.");return;}
        for(int i=0;i<a.length()&&i<max;i++){JSONObject x=a.optJSONObject(i);if(x!=null)content.addView(infoCard(tm.make(x),bm.make(x)));}
    }
    private void renderArrayReverse(String key,int max,TitleMaker tm,BodyMaker bm){
        JSONArray a=snapshot.optJSONArray(key);
        if(empty(a)){emptyState("Aucune donnée.");return;}
        int shown=0;for(int i=a.length()-1;i>=0&&shown<max;i--,shown++){JSONObject x=a.optJSONObject(i);if(x!=null)content.addView(infoCard(tm.make(x),bm.make(x)));}
    }

    private void buildEntryForm() {
        TextView info=text("Cette page crée de nouvelles saisies. La page Dépenses peut aussi reporter une échéance existante.",11,false);
        info.setTextColor(Color.GRAY);content.addView(info);spacer(content,8);

        entryType=new Spinner(this);
        entryType.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Dépense","Revenu","Transaction","Remboursement crédit","Épargne","Événement"}));
        addLabeled(content,"Type de saisie",entryType);

        owner=new Spinner(this);
        owner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Homme","Femme","Commun"}));
        owner.setSelection(2); addLabeled(content,"Personne",owner);

        amount=input("0.00");amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);addLabeled(content,"Montant ($)",amount);
        date=input(isoDate());date.setFocusable(false);date.setOnClickListener(v->pickDate());addLabeled(content,"Date",date);
        time=input(hmTime());time.setFocusable(false);time.setOnClickListener(v->pickTime());addLabeled(content,"Heure",time);

        category=new Spinner(this);
        category.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,categoryLabels()));
        addLabeled(content,"Catégorie",category);

        description=input("Ex. IGA, salaire, rendez-vous...");addLabeled(content,"Description",description);

        sourceAccount=new Spinner(this);
        sourceAccount.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,accountLabels(true)));
        addLabeled(content,"Compte / prélevé sur / déposé dans",sourceAccount);

        destinationBlock=vertical();
        destinationAccount=new Spinner(this);
        destinationAccount.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,accountLabels(false)));
        addLabeled(destinationBlock,"Compte destination (Épargne)",destinationAccount);
        content.addView(destinationBlock);
        destinationBlock.setVisibility(View.GONE);

        debtBlock=vertical();
        debtAccount=new Spinner(this);
        debtAccount.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,debtLabels()));
        addLabeled(debtBlock,"Carte / dette à rembourser",debtAccount);
        content.addView(debtBlock);
        debtBlock.setVisibility(View.GONE);

        note=input("Optionnel");note.setMinLines(3);note.setSingleLine(false);note.setGravity(Gravity.TOP);addLabeled(content,"Note",note);

        entryType.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){
                String type=String.valueOf(entryType.getSelectedItem());
                destinationBlock.setVisibility("Épargne".equals(type)?View.VISIBLE:View.GONE);
                debtBlock.setVisibility("Remboursement crédit".equals(type)?View.VISIBLE:View.GONE);
            }
            public void onNothingSelected(android.widget.AdapterView<?> p){}
        });

        saveButton=primaryButton("✓ Enregistrer la saisie");
        saveButton.setTextSize(16);saveButton.setPadding(dp(10),dp(16),dp(10),dp(16));
        saveButton.setOnClickListener(v->saveEntry());
        content.addView(saveButton,new LinearLayout.LayoutParams(-1,-2));
    }

    private List<String> categoryLabels(){
        List<String> out=new ArrayList<>();out.add("Autres");
        if(snapshot!=null){JSONArray a=snapshot.optJSONArray("categories");if(a!=null)for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x!=null){String n=x.optString("name","");if(!n.isEmpty()&&!out.contains(n))out.add(n);}}}
        return out;
    }
    private List<String> accountLabels(boolean optional){
        List<String> out=new ArrayList<>();if(optional)out.add("— Non lié / autre —");
        if(snapshot!=null){JSONArray a=snapshot.optJSONArray("accounts");if(a!=null)for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x!=null){String n=x.optString("owner","Commun")+" — "+x.optString("institution","")+" — "+x.optString("name","Compte");out.add(n);}}}
        if(out.isEmpty())out.add("Compte non défini");
        return out;
    }

    private List<String> debtLabels(){
        debtIds.clear();List<String> out=new ArrayList<>();
        if(snapshot!=null){JSONArray a=snapshot.optJSONArray("debts");if(a!=null)for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x!=null&&x.optDouble("balance",0)>0){debtIds.add(x.optString("id",""));out.add(x.optString("owner","Commun")+" — "+x.optString("name","Dette")+" — "+money(x.optDouble("balance",0)));}}}
        if(out.isEmpty()){debtIds.add("");out.add("Aucune dette disponible");}
        return out;
    }

    private String selectedDebtId(){
        int p=debtAccount==null?0:debtAccount.getSelectedItemPosition();
        return (p>=0&&p<debtIds.size())?debtIds.get(p):"";
    }

    private void saveEntry() {
        if(!ensureWorkbookForWrite())return;
        String uiType=String.valueOf(entryType.getSelectedItem());
        String desc=description.getText().toString().trim();
        if(desc.isEmpty())desc=uiType+" téléphone";
        double a=0;try{a=Double.parseDouble(amount.getText().toString().trim().replace(',','.'));}catch(Exception ignored){}
        if(!"Événement".equals(uiType)&&a<=0){toast("Indiquez un montant supérieur à 0.");return;}

        String type=uiType;
        if("Transaction".equals(uiType)) type="Dépense"; // transaction simple = retrait; revenu a son propre bouton.

        XlsxAppender.Entry e=new XlsxAppender.Entry();
        e.date=date.getText().toString();
        e.time=time.getText().toString();
        e.owner=String.valueOf(owner.getSelectedItem());
        e.type=type;
        e.amount=a;
        e.category=String.valueOf(category.getSelectedItem());
        e.description=desc;
        String src=String.valueOf(sourceAccount.getSelectedItem());
        if(src.startsWith("—"))src="";
        e.account=src;
        String userNote=note.getText().toString().trim();
        if("Épargne".equals(uiType)){
            String dest=String.valueOf(destinationAccount.getSelectedItem());
            if(dest.isEmpty()||dest.equals(src)){toast("Choisissez un compte destination différent.");return;}
            e.category="Épargne";
            e.note="DESTINATION="+dest+"; "+userNote;
        }else if("Remboursement crédit".equals(uiType)){
            String did=selectedDebtId();
            if(did.isEmpty()){toast("Choisissez une carte ou une dette à rembourser.");return;}
            e.type="Remboursement crédit";
            e.category="Remboursement dette supplémentaire";
            e.note="DEBT_ID="+did+"; "+userNote;
        }else e.note=userNote;

        saveButton.setEnabled(false);
        new Thread(()->{
            try{
                XlsxAppender.append(getContentResolver(),workbookUri,e);
                runOnUiThread(()->{
                    saveButton.setEnabled(true);amount.setText("");description.setText("");note.setText("");time.setText(hmTime());
                    toast("Saisie enregistrée. Couple Finance PC l'importera.");
                });
            }catch(Exception ex){
                runOnUiThread(()->{saveButton.setEnabled(true);toast("Erreur d'écriture : "+ex.getMessage());});
            }
        }).start();
    }

    private class ExpenseDonutView extends View{
        private final List<Double> values;private final double sum;private final double total;private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] colors={Color.rgb(51,143,238),Color.rgb(246,53,78),Color.rgb(91,174,100),Color.rgb(255,177,47),Color.rgb(132,79,221),Color.rgb(70,194,120)};
        ExpenseDonutView(List<Double> v,double s,double t){super(MainActivity.this);values=v;sum=s;total=t;}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight(),pad=dp(12),size=Math.min(w,h)-2*pad;RectF r=new RectF((w-size)/2,(h-size)/2,(w+size)/2,(h+size)/2);float start=-90;
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(22));p.setStrokeCap(Paint.Cap.BUTT);
            for(int i=0;i<values.size();i++){float sw=(float)(values.get(i)/Math.max(1,sum)*360.0);p.setColor(colors[i%colors.length]);c.drawArc(r,start,sw,false,p);start+=sw;}
            p.setStyle(Paint.Style.FILL);p.setColor(Color.rgb(7,45,84));p.setTextAlign(Paint.Align.CENTER);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(dp(12));c.drawText(money(total),w/2,h/2,p);p.setTypeface(Typeface.DEFAULT);p.setTextSize(dp(9));c.drawText("Total",w/2,h/2+dp(16),p);
        }
    }

    private View kpiCard(String title,String value,String sub,boolean positive){
        LinearLayout c=cardBox();
        TextView t=text(title.toUpperCase(Locale.CANADA_FRENCH),11,true);t.setTextColor(Color.rgb(83,110,135));c.addView(t);
        TextView v=text(value,22,true);v.setTextColor(positive?Color.rgb(18,120,78):Color.rgb(173,49,57));c.addView(v);
        TextView s=text(sub,12,false);s.setTextColor(Color.DKGRAY);c.addView(s);
        return c;
    }
    private View infoCard(String title,String body){
        LinearLayout c=cardBox();
        TextView t=text(title,15,true);t.setTextColor(Color.rgb(7,51,94));c.addView(t);
        TextView b=text(body,12,false);b.setTextColor(Color.DKGRAY);b.setPadding(0,dp(4),0,0);c.addView(b);
        return c;
    }
    private TextView sectionLabel(String s){TextView t=text(s,16,true);t.setPadding(0,dp(14),0,dp(5));t.setTextColor(Color.rgb(7,51,94));return t;}
    private void emptyState(String s){content.addView(infoCard("Aucune donnée",s));}
    private boolean empty(JSONArray a){return a==null||a.length()==0;}

    private LinearLayout cardBox(){
        LinearLayout c=vertical();c.setPadding(dp(12),dp(10),dp(12),dp(10));
        GradientDrawable g=new GradientDrawable();g.setColor(Color.WHITE);g.setCornerRadius(dp(18));g.setStroke(dp(1),Color.rgb(225,232,240));c.setBackground(g);c.setElevation(dp(2));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(5),0,dp(5));c.setLayoutParams(p);return c;
    }
    private Button smallNavButton(String s){Button b=button(s);b.setTextSize(12);b.setPadding(dp(10),dp(5),dp(10),dp(5));return b;}
    private Button modernNavButton(String s){
        Button b=button(s);b.setTextSize(12);b.setTextColor(Color.rgb(7,51,94));b.setTypeface(null,Typeface.BOLD);b.setPadding(dp(6),dp(13),dp(6),dp(13));
        GradientDrawable g=new GradientDrawable();g.setColor(Color.WHITE);g.setCornerRadius(dp(14));g.setStroke(dp(1),Color.rgb(220,229,238));b.setBackground(g);b.setElevation(dp(1));return b;
    }
    private Button primaryButton(String s){
        Button b=button(s);b.setTextColor(Color.WHITE);b.setTypeface(null,Typeface.BOLD);
        GradientDrawable g=new GradientDrawable();g.setColor(Color.rgb(18,102,210));g.setCornerRadius(dp(14));b.setBackground(g);b.setElevation(dp(2));return b;
    }
    private String money(double n){return String.format(Locale.CANADA_FRENCH,"%,.2f $",n).replace('\u00A0',' ');}
    private String fmt(double n){return String.format(Locale.CANADA_FRENCH,"%.2f",n);}
    private void pickDate(){final Calendar c=Calendar.getInstance();new DatePickerDialog(this,(v,y,m,d)->date.setText(String.format(Locale.CANADA_FRENCH,"%04d-%02d-%02d",y,m+1,d)),c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show();}
    private void pickTime(){final Calendar c=Calendar.getInstance();new TimePickerDialog(this,(v,h,m)->time.setText(String.format(Locale.CANADA_FRENCH,"%02d:%02d",h,m)),c.get(Calendar.HOUR_OF_DAY),c.get(Calendar.MINUTE),true).show();}
    private static String isoDate(){return new SimpleDateFormat("yyyy-MM-dd",Locale.CANADA_FRENCH).format(new Date());}
    private static String hmTime(){return new SimpleDateFormat("HH:mm",Locale.CANADA_FRENCH).format(new Date());}

    private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout horizontal(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    private LinearLayout.LayoutParams weight(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(dp(2),dp(2),dp(2),dp(2));return p;}
    private TextView text(String s,int sp,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);return b;}
    private EditText input(String hint){EditText e=new EditText(this);e.setHint(hint);e.setSingleLine(true);return e;}
    private void addLabeled(LinearLayout host,String label,View v){TextView l=text(label,12,true);l.setPadding(0,dp(8),0,0);host.addView(l);host.addView(v,new LinearLayout.LayoutParams(-1,-2));}
    private void spacer(LinearLayout host,int h){View v=new View(this);host.addView(v,new LinearLayout.LayoutParams(1,dp(h)));}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
}